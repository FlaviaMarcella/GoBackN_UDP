package org.unifal.flavia.renan;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

public class Receiver {

    public static void main(String[] args) throws Exception {

        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(Integer.parseInt(args[0]));
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // Criamos o arquivo de saída no disco
        FileOutputStream fos = null;

        // 1024 de payload + 11 de cabeçalho
        byte[] receiveData = new byte[1035];
        int expectedseqnum = 0;
        boolean end = false;
        boolean handshakeRecebido = false;
        String expectedMd5 = "";
        String nomeArquivoDestino = "";

        // Variáveis de controle para estatísticas e simulação
        double probPerda = 0.0;
        int totalPacotesRecebidos = 0;   // Pacotes de dados recebidos EM ORDEM (candidatos à simulação de perda)
        int pacotesDescartados = 0;      // Quantos desses foram descartados pela simulação de perda
        int pacotesForaDeOrdem = 0;      // Apenas contabilizados à parte, nunca entram na taxa de perda simulada
        long tamanhoArquivoEsperado = -1;
        long bytesRecebidos = 0;
        long tempoInicioTransferencia = 0;

        System.out.println("Receptor aguardando na porta " + args[0] + "...");

        while (!end) {

            // 1. Recebe o pacote do Emissor
            DatagramPacket packet = new DatagramPacket(receiveData, receiveData.length);
            socket.receive(packet);

            // 2. Extrai o cabeçalho do pacote recebido
            ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
            byte tipo = buffer.get();
            int numSeq = buffer.getInt();
            int numAck = buffer.getInt();
            short tamanhoDados = buffer.getShort();

            // 3. Processa os dados ou encerra a conexão (Lógica de Handshake)
            if (tipo == 2) {
                if (!handshakeRecebido) {
                    byte[] payload = new byte[tamanhoDados];
                    buffer.get(payload);
                    String handshakeInfo = new String(payload);
                    String[] parametros = handshakeInfo.split(";");

                    probPerda = Double.parseDouble(parametros[0]);
                    nomeArquivoDestino = parametros[1];

                    // Usa o tamanho do arquivo enviado no handshake (seção 3.2 do enunciado)
                    // para poder exibir o progresso percentual da transferência.
                    if (parametros.length > 2) {
                        try {
                            tamanhoArquivoEsperado = Long.parseLong(parametros[2]);
                        } catch (NumberFormatException ignored) {
                            tamanhoArquivoEsperado = -1;
                        }
                    }

                    if (parametros.length > 3) { // Verifica se o hash foi enviado
                        expectedMd5 = parametros[3];
                    }

                    File fileDestino = new File(nomeArquivoDestino);

                    if (fileDestino.getParentFile() != null) fileDestino.getParentFile().mkdirs();
                    fos = new FileOutputStream(fileDestino);

                    handshakeRecebido = true;
                    tempoInicioTransferencia = System.currentTimeMillis();
                    System.out.println("Handshake recebido! Prob: " + probPerda
                            + " | Arquivo: " + nomeArquivoDestino
                            + " | Tamanho esperado: " + (tamanhoArquivoEsperado >= 0 ? tamanhoArquivoEsperado + " bytes" : "desconhecido")
                            + " | Hash recebido: " + (expectedMd5.isEmpty() ? "(nenhum)" : expectedMd5));
                }
            }
            // Lógica de Dados com Simulação de Perda
            else if (tipo == 0) {

                // IMPORTANTE (Seção 4 do enunciado): a simulação de perda deve
                // atuar SOMENTE sobre pacotes recebidos corretamente EM ORDEM.
                // Pacotes fora de ordem/duplicados já são descartados pela
                // própria lógica do GBN e NÃO podem ser contabilizados como
                // perda simulada nem participar do sorteio. Por isso a
                // verificação de sequência vem ANTES do sorteio de perda.
                if (numSeq != expectedseqnum) {
                    pacotesForaDeOrdem++;
                    System.out.println("Pacote " + numSeq + " fora de ordem. Esperado: " + expectedseqnum
                            + " (descartado pela lógica do GBN, não contabilizado como perda simulada)");

                    // Reenvia o último ACK cumulativo válido, como manda a FSM do receptor GBN
                    enviarAck(socket, packet, expectedseqnum - 1);
                    continue;
                }

                // A partir daqui, o pacote está em ordem: é candidato à simulação de perda.
                totalPacotesRecebidos++;

                if (Math.random() < probPerda) {
                    pacotesDescartados++;
                    System.out.println("[SIMULAÇÃO] Pacote " + numSeq + " descartado silenciosamente!");
                    continue; // Descarta e volta para o início do loop sem enviar ACK
                }

                byte[] payload = new byte[tamanhoDados];
                buffer.get(payload);
                fos.write(payload);
                bytesRecebidos += tamanhoDados;
                expectedseqnum++;

                double progresso = tamanhoArquivoEsperado > 0
                        ? (100.0 * bytesRecebidos / tamanhoArquivoEsperado)
                        : -1;
                double segundosDecorridos = tempoInicioTransferencia > 0
                        ? (System.currentTimeMillis() - tempoInicioTransferencia) / 1000.0
                        : 0;
                double throughputKBps = segundosDecorridos > 0
                        ? (bytesRecebidos / 1024.0) / segundosDecorridos
                        : 0.0;

                if (progresso >= 0) {
                    System.out.printf("Recebido seq %d | Gravado. Progresso: %.1f%% | throughput=%.2f KB/s%n",
                            numSeq, progresso, throughputKBps);
                } else {
                    System.out.println("Recebido seq " + numSeq + " | Gravado.");
                }

                // Envio de ACK cumulativo
                enviarAck(socket, packet, expectedseqnum - 1);
            } else if (tipo == 3) {
                end = true;
                System.out.println("Sinal de FIM recebido.");
            }
        }

        fos.close();

        if (!expectedMd5.isEmpty()) { // Apenas executa se um hash foi recebido no handshake
            System.out.println("\n================ VERIFICAÇÃO DE INTEGRIDADE MD5 ================");
            try {
                File receivedFile = new File(nomeArquivoDestino);
                // 1. Calcula o hash do arquivo que acabou de ser salvo no disco
                String calculatedMd5 = calculateMD5(receivedFile);

                System.out.println("   Hash Esperado:  " + expectedMd5);
                System.out.println("   Hash Calculado: " + calculatedMd5);

                // 2. COMPARAÇÃO DIRETA DAS STRINGS DE HASH
                if (expectedMd5.equals(calculatedMd5)) {
                    System.out.println("   Resultado: SUCESSO! O arquivo está íntegro.");
                } else {
                    System.out.println("   Resultado: FALHA! O arquivo pode estar corrompido.");
                }
                System.out.println("================================================================");

            } catch (Exception e) {
                System.out.println("   Erro ao calcular o hash do arquivo recebido: " + e.getMessage());
            }
        }

        // Exibição de Estatísticas (R6)
        System.out.println("\n================ ESTATÍSTICAS DO RECEPTOR ================");
        System.out.println("Total de pacotes em ordem recebidos (candidatos à simulação): " + totalPacotesRecebidos);
        System.out.println("Total de pacotes descartados pela simulação de perda: " + pacotesDescartados);
        System.out.println("Total de pacotes fora de ordem/duplicados (não contam na perda simulada): " + pacotesForaDeOrdem);
        if (totalPacotesRecebidos > 0) {
            // Taxa calculada apenas sobre pacotes em ordem, conforme a Seção 4 do
            // enunciado: deve tender à probPerda configurada pela Lei dos Grandes Números.
            double taxaPerda = (double) pacotesDescartados / totalPacotesRecebidos;
            System.out.printf("Taxa de perda efetiva (sobre pacotes em ordem): %.2f%% (configurada: %.2f%%)\n",
                    taxaPerda * 100, probPerda * 100);
        }
        System.out.println("==========================================================");
    }

    // Monta e envia um pacote de ACK cumulativo (tipo 1) para o endereço/porta
    // de origem do pacote recebido. Extraído em método próprio pois agora é
    // chamado tanto para pacotes em ordem quanto para reenviar o último ACK
    // em caso de pacote fora de ordem (conforme a FSM do receptor GBN).
    private static void enviarAck(DatagramSocket socket, DatagramPacket origem, int numAck) throws Exception {
        ByteBuffer ackBuffer = ByteBuffer.allocate(11);
        ackBuffer.put((byte) 1);
        ackBuffer.putInt(0);
        ackBuffer.putInt(numAck);
        ackBuffer.putShort((short) 0);

        byte[] ackData = ackBuffer.array();
        DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length, origem.getAddress(), origem.getPort());
        socket.send(ackPacket);
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