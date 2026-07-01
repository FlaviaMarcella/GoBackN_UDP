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
        int totalPacotesRecebidos = 0;
        int pacotesDescartados = 0;

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
                    System.out.println("Receptor aguardando na porta " + args[0] + "...");

                    byte[] payload = new byte[tamanhoDados];
                    buffer.get(payload);
                    String handshakeInfo = new String(payload);
                    String[] parametros = handshakeInfo.split(";");

                    System.out.println("Prob Perda: " + parametros[0]);

                    probPerda = Double.parseDouble(parametros[0]);
                    nomeArquivoDestino = parametros[1];

                    if (parametros.length > 3) { // Verifica se o hash foi enviado
                        expectedMd5 = parametros[3];
                        System.out.println("Hash MD5 recebido: " + expectedMd5);
                    }

                    File fileDestino = new File(nomeArquivoDestino);

                    if (fileDestino.getParentFile() != null) fileDestino.getParentFile().mkdirs();
                    fos = new FileOutputStream(fileDestino);

                    handshakeRecebido = true;
                    System.out.println("Handshake recebido! Prob: " + probPerda + " | Arquivo: " + nomeArquivoDestino + " | Hash recebido: " + expectedMd5);
                }
            }
            // Lógica de Dados com Simulação de Perda
            else if (tipo == 0) {
                totalPacotesRecebidos++;

                // Simulação de perda antes de qualquer processamento
                if (Math.random() < probPerda) {
                    pacotesDescartados++;
                    System.out.println("[SIMULAÇÃO] Pacote " + numSeq + " descartado silenciosamente!");
                    continue; // Descarta e volta para o início do loop sem enviar ACK
                }

                if (numSeq == expectedseqnum) {
                    byte[] payload = new byte[tamanhoDados];
                    buffer.get(payload);
                    fos.write(payload);
                    expectedseqnum++;
                    System.out.println("Recebido seq " + numSeq + " | Gravado.");
                } else {
                    System.out.println("Pacote " + numSeq + " fora de ordem. Esperado: " + expectedseqnum);
                }

                // Envio de ACK cumulativo
                ByteBuffer ackBuffer = ByteBuffer.allocate(11);
                ackBuffer.put((byte) 1);
                ackBuffer.putInt(0);
                ackBuffer.putInt(expectedseqnum - 1);
                ackBuffer.putShort((short) 0);

                byte[] ackData = ackBuffer.array();
                DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length, packet.getAddress(), packet.getPort());
                socket.send(ackPacket);
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
        System.out.println("Total de pacotes de dados processados: " + totalPacotesRecebidos);
        System.out.println("Total de pacotes descartados: " + pacotesDescartados);
        if (totalPacotesRecebidos > 0) {
            double taxaPerda = (double) pacotesDescartados / totalPacotesRecebidos;
            System.out.printf("Taxa de perda efetiva: %.2f%%\n", taxaPerda * 100);
        }
        System.out.println("==========================================================");
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