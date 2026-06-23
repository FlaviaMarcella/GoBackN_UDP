package org.unifal.flavia.renan;

import java.io.File;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

public class Sender {

    static volatile int base = 0;  // Número de sequência do pacote mais antigo não confirmado
    static volatile int nextseqnum = 0; // Próximo número de sequência a ser usado
    static Timer timer = new Timer();
    static TimerTask timeoutTask;

    // Método para iniciar/reiniciar o cronômetro
    static void iniciarTimer(DatagramSocket socket, DatagramPacket[] janela, int N) {
        if (timeoutTask != null) {
            timeoutTask.cancel(); // Cancela o cronômetro antigo
        }

        timeoutTask = new TimerTask() {
            @Override
            public void run() {
                // Se estourar o tempo, retransmite tudo que não foi confirmado!
                System.out.println("TIMEOUT! Retransmitindo da base " + base + " até " + (nextseqnum - 1));
                for (int i = base; i < nextseqnum; i++) {
                    try {
                        socket.send(janela[i % N]);
                        System.out.println("Retransmitido pacote seq " + i);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        // Agenda para estourar em 1000 milissegundos (1 segundo)
        timer.schedule(timeoutTask, 1000);
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

        // 3. O nosso Buffer Circular da Janela [cite: 24]
        // Um array simples que vai guardar os pacotes enviados e aguardando ACK
        DatagramPacket[] janela = new DatagramPacket[N];

        System.out.println("Emissor iniciado! Janela N=" + N + " | Prob. Perda=" + probPerda);

        // 1. Pegando as informações do arquivo original
        File arquivo = new File(arquivoOrigem);
        long tamanhoArquivo = arquivo.length();
        FileInputStream fis = new FileInputStream(arquivo);

        // 2. Fundindo a pasta destino com o nome do arquivo
        // Se o usuário digitou uma pasta (terminada em /), nós adicionamos o nome do arquivo no final
        if (pathDestino.endsWith("/") || pathDestino.endsWith("\\")) {
            pathDestino = pathDestino + arquivo.getName();
        }

        // 3. Montando o payload do Handshake com o caminho novo (ex: Recebido/reuniao.txt)
        String handshakeInfo = probPerda + ";" + pathDestino + ";" + tamanhoArquivo;
        byte[] handshakePayload = handshakeInfo.getBytes();

        ByteBuffer handshakeBuffer = ByteBuffer.allocate(11 + handshakePayload.length);
        handshakeBuffer.put((byte) 2); // Tipo 0 = DADOS
        handshakeBuffer.putInt(-1); // Número de sequência
        handshakeBuffer.putInt(0); // Número de ACK (não utilizado)
        handshakeBuffer.putShort((short) handshakePayload.length); // Tamanho dos dados
        handshakeBuffer.put(handshakePayload); // Dados úteis

        DatagramPacket packet = new DatagramPacket(handshakeBuffer.array(), handshakeBuffer.array().length, ipDestino, portaDestino);

        // Enviando o Handshake 5 vezes para garantir que o Receptor receba e abra o arquivo
        for (int i = 0; i < 5; i++) {
            toReceiver.send(packet);
        }

        System.out.println("Enviado Handshake com base=" + base + " e nextseqnum=" + nextseqnum);
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
                                if (timeoutTask != null) {
                                    timeoutTask.cancel();
                                    System.out.println("Todos os pacotes confirmados. Cronômetro cancelado.");
                                }
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

            // 1. ESPERAR SE A JANELA ESTIVER CHEIA (Trocado para while!)
            while (nextseqnum >= base + N) {
                System.out.println("Janela cheia! Aguardando ACKs...");
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

            // Salva na posição correta do círculo
            janela[nextseqnum % N] = dataPacket;
            System.out.println("Enviado pacote seq " + nextseqnum);

            if (base == nextseqnum) {
                iniciarTimer(toReceiver, janela, N);
            }

            // 4. INCREMENTAR O nextseqnum
            nextseqnum++;
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

        // Desliga o timer global e fecha a conexão com o receptor
        timer.cancel();
        toReceiver.close();
        System.exit(0); // Encerra o programa, forçando a tread de ACK a terminar também
    }

}
