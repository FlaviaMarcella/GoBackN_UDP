package org.unifal.flavia.renan;

import java.io.File;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Timer;
import java.util.TimerTask;

public class Sender {

    static volatile int base = 0;  // Número de sequência do pacote mais antigo não confirmado
    static volatile int nextseqnum = 0; // Próximo número de sequência a ser usado
    static int totalPacotesEnviados = 0;
    // Lock dedicado exclusivamente à proteção do buffer circular "janela".
    // Ele é escrito pela thread principal (ao enviar novos pacotes) e lido
    // pela thread do Timer (ao retransmitir em caso de timeout), portanto
    // precisa de sincronização própria - independente do lock usado para
    // iniciar/parar o timer.
    static final Object janelaLock = new Object();
    static int totalEventosTimeout = 0;      // Quantas vezes o timer estourou
    static int totalPacotesRetransmitidos = 0; // Quantos pacotes individuais foram reenviados

    static Timer timer = new Timer();
    static TimerTask timeoutTask;
    static long totalBytesEnviados = 0; // Para cálculo de throughput (inclui retransmissões físicas)

    // Método para iniciar/reiniciar o cronômetro (com synchronized!)
    static synchronized void iniciarTimer(DatagramSocket socket, DatagramPacket[] janela, int N) {
        if (timeoutTask != null) {
            timeoutTask.cancel(); // Cancela o cronômetro antigo
        }

        timeoutTask = new TimerTask() {
            @Override
            public void run() {
                // Se estourar o tempo, retransmite tudo que não foi confirmado!
                totalEventosTimeout++;
                System.out.println("TIMEOUT! Retransmitindo da base " + base + " até " + (nextseqnum - 1));
                // Acessa o buffer circular sob o mesmo lock usado na escrita,
                // garantindo visibilidade correta entre a thread principal
                // (que grava pacotes novos em janela[]) e esta thread do Timer.
                synchronized (janelaLock) {
                    for (int i = base; i < nextseqnum; i++) {
                        try {
                            DatagramPacket pacoteParaReenviar = janela[i % N];
                            if (pacoteParaReenviar != null) {
                                socket.send(pacoteParaReenviar);
                                totalPacotesRetransmitidos++;
                                totalBytesEnviados += pacoteParaReenviar.getLength();
                                System.out.println("Retransmitido pacote seq " + i);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        };
        // Agenda para estourar em 100 milissegundos (0.1 segundo)
        timer.schedule(timeoutTask, 100, 100);
    }

    // Método para parar o cronômetro (com synchronized!)
    static synchronized void pararTimer() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    public static void main(String[] args) throws Exception {

        // 1. Lendo os argumentos da linha de comando
        String arquivoOrigem = args[0];
        String ipDestinoStr = args[1].split(":")[0];
        String pathDestino = args[1].split(":")[1]; // Alterado para ler o path
        int N = Integer.parseInt(args[2]); // Tamanho da janela de transmissão
        double probPerda = Double.parseDouble(args[3]); // Probabilidade de perda

        // (Assumindo que o receptor está na 5000)
        int portaDestino = 5000;
        InetAddress ipDestino = InetAddress.getByName(ipDestinoStr);

        DatagramSocket toReceiver = new DatagramSocket();

        // 3. O Buffer Circular da Janela
        // Um array simples que vai guardar os pacotes enviados e aguardando ACK
        DatagramPacket[] janela = new DatagramPacket[N];

        System.out.println("Emissor iniciado! Janela N=" + N + " | Prob. Perda=" + probPerda);

        // 1. Pegando as informações do arquivo original
        File arquivo = new File(arquivoOrigem);
        long tamanhoArquivo = arquivo.length();
        FileInputStream fis = new FileInputStream(arquivo);

        // Calculo do hash MD5 do arquivo
        String md5Hash = calculateMD5(arquivo);
        System.out.println("MD5 do arquivo original: " + md5Hash);

        // 2. Fundindo a pasta destino com o nome do arquivo
        // Se o usuário digitou uma pasta (terminada em /), nós adicionamos o nome do arquivo no final
        if (pathDestino.endsWith("/") || pathDestino.endsWith("\\")) {
            pathDestino = pathDestino + arquivo.getName();
        }

        // 3. Montando o payload do Handshake com o caminho novo (ex: Recebido/reuniao.txt)
        String handshakeInfo = probPerda + ";" + pathDestino + ";" + tamanhoArquivo + ";" + md5Hash;
        byte[] handshakePayload = handshakeInfo.getBytes();

        ByteBuffer handshakeBuffer = ByteBuffer.allocate(11 + handshakePayload.length);
        handshakeBuffer.put((byte) 2); // Tipo 2 = HANDSHAKE
        handshakeBuffer.putInt(-1); // Número de sequência
        handshakeBuffer.putInt(0); // Número de ACK (não utilizado)
        handshakeBuffer.putShort((short) handshakePayload.length); // Tamanho dos dados
        handshakeBuffer.put(handshakePayload); // Dados úteis

        DatagramPacket packet = new DatagramPacket(handshakeBuffer.array(), handshakeBuffer.array().length, ipDestino, portaDestino);

        long tempoInicio = System.currentTimeMillis();

        // Enviando o Handshake 5 vezes para garantir que o Receptor receba e abra o arquivo
        for (int i = 0; i < 5; i++) {
            toReceiver.send(packet);
        }

        System.out.println("Enviado Handshake com base=" + base + " e nextseqnum=" + nextseqnum);

        //Thread recebedora de ACKs
        Runnable runnable = () -> {
            try {
                // 3. Loop para receber os ACKs do receptor
                byte[] ackBuffer = new byte[11];
                DatagramPacket ackPacket = new DatagramPacket(ackBuffer, ackBuffer.length);

                while (true) {
                    toReceiver.receive(ackPacket);
                    ByteBuffer buffer = ByteBuffer.wrap(ackPacket.getData());
                    byte tipo = buffer.get();
                    int numSeq = buffer.getInt();
                    int numAck = buffer.getInt();
                    short tamanhoDados = buffer.getShort();

                    if (tipo == 1) { // TIPO 1 = ACK
                        System.out.println("Recebido ACK " + numAck);
                        if (numAck >= base) {
                            base = numAck + 1; // Avança a base da janela
                            System.out.println("Base da janela avançada para " + base);

                            if (base == nextseqnum) {
                                // Se todos os pacotes foram confirmados, cancela o cronômetro
                                pararTimer();
                                System.out.println("Todos os pacotes confirmados. Cronômetro cancelado.");

                            } else {
                                // Reinicia o cronômetro para o próximo pacote não confirmado
                                iniciarTimer(toReceiver, janela, N);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Se o erro for apenas o socket fechando no final do programa, ignoramos em paz.
                if (e instanceof java.net.SocketException) {
                    System.out.println("Thread de recepção de ACKs encerrada.");
                } else {
                    // Se for um erro real, mostramos na tela.
                    e.printStackTrace();
                }
            }
        };

        Thread ackThread = new Thread(runnable);
        ackThread.start();

        byte[] payload = new byte[1024];
        int bytesRead;

        while ((bytesRead = fis.read(payload)) != -1) {

            boolean avisouJanela = false; //Controle para não gerar mensagens excessivas no terminal

            // 1. ESPERAR SE A JANELA ESTIVER CHEIA (Trocado para while!)
            while (nextseqnum >= base + N) {
                if (!avisouJanela) {
                    System.out.println("Janela cheia! Aguardando ACKs...");
                    avisouJanela = true;
                }
                Thread.sleep(10);
            }

            // 2. MONTAR O DATAGRAMA DE DADOS (TIPO 0)
            ByteBuffer buffer = ByteBuffer.allocate(11 + bytesRead);
            buffer.put((byte) 0);
            buffer.putInt(nextseqnum);
            buffer.putInt(0);
            buffer.putShort((short) bytesRead);
            buffer.put(payload, 0, bytesRead);

            // 3. ENVIAR PARA O RECEPTOR E SALVAR NO BUFFER CIRCULAR
            DatagramPacket dataPacket = new DatagramPacket(buffer.array(), buffer.array().length, ipDestino, portaDestino);
            toReceiver.send(dataPacket);
            totalPacotesEnviados++;
            totalBytesEnviados += dataPacket.getLength();

            // Salva na posição correta do círculo, protegido pelo lock,
            // pois a thread do Timer pode ler este array concorrentemente
            // em caso de timeout.
            synchronized (janelaLock) {
                janela[nextseqnum % N] = dataPacket;
            }

            if (base == nextseqnum) {
                iniciarTimer(toReceiver, janela, N);
            }

            // 4. INCREMENTAR O nextseqnum
            nextseqnum++;

            // Progresso em tempo real: pacotes enviados, ACKs confirmados (base)
            // e throughput estimado (KB/s) desde o início da transferência.
            double segundosDecorridos = (System.currentTimeMillis() - tempoInicio) / 1000.0;
            double throughputKBps = segundosDecorridos > 0
                    ? (totalBytesEnviados / 1024.0) / segundosDecorridos
                    : 0.0;
            System.out.printf("Enviado pacote seq %d | base=%d | pacotes enviados=%d | throughput=%.2f KB/s%n",
                    nextseqnum - 1, base, totalPacotesEnviados, throughputKBps);
        }
        fis.close();

        while (base != nextseqnum) {
            System.out.println("Aguardando ACKs...");
            Thread.sleep(100);
        }

        System.out.println("Todos os pacotes de dados foram confirmados! Enviando sinal de FIM...");

        // Monta o pacote de FIM (11 bytes de cabeçalho, TIPO 3)
        ByteBuffer fimBuffer = ByteBuffer.allocate(11);
        fimBuffer.put((byte) 3); // TIPO 3 = FIM
        fimBuffer.putInt(nextseqnum);
        fimBuffer.putInt(0);
        fimBuffer.putShort((short) 0); // Sem payload

        DatagramPacket fimPacket = new DatagramPacket(fimBuffer.array(), fimBuffer.array().length, ipDestino, portaDestino);

        // Envia 5 vezes o FIM para garantir que todos os ACKs cheguem
        for (int i = 0; i < 5; i++) {
            toReceiver.send(fimPacket);
        }

        System.out.println("Enviado sinal de FIM. Encerrando Emissor.");

        long tempoFim = System.currentTimeMillis(); // MARCA O FIM!
        long tempoTotalMs = tempoFim - tempoInicio;
        double tempoTotalSegundos = tempoTotalMs / 1000.0; // Converte para segundos

        double throughputMedioKBps = tempoTotalSegundos > 0
                ? (totalBytesEnviados / 1024.0) / tempoTotalSegundos
                : 0.0;

        System.out.println("\n================ ESTATÍSTICAS DO EMISSOR ================");
        System.out.println("Total de pacotes de dados originais (segmentos do arquivo): " + totalPacotesEnviados);
        System.out.println("Total de eventos de timeout (baterias de retransmissão): " + totalEventosTimeout);
        System.out.println("Total de pacotes individuais retransmitidos: " + totalPacotesRetransmitidos);
        System.out.println("Total de pacotes enviados fisicamente (originais + retransmitidos): "
                + (totalPacotesEnviados + totalPacotesRetransmitidos));
        System.out.println("Total de bytes de dados transmitidos fisicamente: " + totalBytesEnviados);
        System.out.printf("Throughput médio efetivo: %.2f KB/s%n", throughputMedioKBps);
        System.out.printf("TEMPO TOTAL DE TRANSFERÊNCIA: %.3f segundos\n", tempoTotalSegundos);
        System.out.println("=========================================================\n");

        // Desliga o timer global e fecha a conexão com o receptor
        timer.cancel();
        toReceiver.close();
        System.exit(0); // Encerra o programa, forçando a tread de ACK a terminar também
    }

    private static String calculateMD5(File file) throws Exception {
        MessageDigest md5Digest = MessageDigest.getInstance("MD5");
        FileInputStream fis = new FileInputStream(file);

        byte[] byteArray = new byte[1024];
        int bytesCount = 0;

        while ((bytesCount = fis.read(byteArray)) != -1) {
            md5Digest.update(byteArray, 0, bytesCount);
        }
        fis.close();

        byte[] bytes = md5Digest.digest();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }

}