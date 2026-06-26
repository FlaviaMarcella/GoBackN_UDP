package org.unifal.flavia.renan;

import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;

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

        System.out.println("Receptor aguardando na porta " + args[0] + "...");

        while (!end) {

            double probPerdaSessao = 0.0;

            // 1. Recebe o pacote do Emissor
            DatagramPacket packet = new DatagramPacket(receiveData, receiveData.length);
            socket.receive(packet);

            // 2. Extrai o cabeçalho do pacote recebido
            ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
            byte tipo = buffer.get();
            int numSeq = buffer.getInt();
            int numAck = buffer.getInt();
            short tamanhoDados = buffer.getShort();

            // 3. Processa os dados ou encerra a conexão
            if (numSeq == expectedseqnum && tipo == 0) {
                // Lemos EXATAMENTE a quantidade de dados úteis
                byte[] payload = new byte[tamanhoDados];
                buffer.get(payload);

                // Gravamos direto no disco
                fos.write(payload);
                expectedseqnum++;
                System.out.println("Recebido pacote seq " + numSeq + " | Gravando " + tamanhoDados + " bytes");

            } else if (tipo == 2) {
                // TIPO 2 = HANDSHAKE
                byte[] payload = new byte[tamanhoDados];
                buffer.get(payload);
                String handshakeInfo = new String(payload);

                // Quebra a string "prob;nome;tamanho"
                String[] parametros = handshakeInfo.split(";");
                probPerdaSessao = Double.parseDouble(parametros[0]);
                String nomeArquivoDestino = parametros[1]; // Pega o caminho completo

                // Transforma a string em um objeto File para podermos manipular as pastas
                File fileDestino = new File(nomeArquivoDestino);

                // Se o arquivo tiver uma pasta "pai" (ex: Recebido/), o Java cria a pasta no seu Windows!
                if (fileDestino.getParentFile() != null) {
                    fileDestino.getParentFile().mkdirs();
                }

                // Criamos o arquivo dentro da pasta certa!
                fos = new FileOutputStream(fileDestino);
                System.out.println("Handshake recebido! Criando arquivo em: " + fileDestino.getPath());
            } else if (tipo == 3) { // TIPO 3 = FIM
                end = true;
                System.out.println("Sinal de FIM recebido. Encerrando gravação!");
            } else {
                System.out.println("Pacote descartado! (Fora de ordem ou duplicado)");
            }

            // 4. Montando o buffer do ACK de resposta
            ByteBuffer ackBuffer = ByteBuffer.allocate(11);
            ackBuffer.put((byte) 1);              // TIPO 1 = ACK
            ackBuffer.putInt(0);                  // num_seq (não utilizado)
            ackBuffer.putInt(expectedseqnum - 1); // num_ack = o pacote que estamos confirmando
            ackBuffer.putShort((short) 0);        // tamanho_dados = 0

            // 5. Extraindo os bytes e criando o DatagramPacket de resposta
            byte[] ackData = ackBuffer.array();
            DatagramPacket ackPacketToSend = new DatagramPacket(
                    ackData,
                    ackData.length,
                    packet.getAddress(), // Devolvemos para o IP de onde veio o pacote
                    packet.getPort()     // Devolvemos para a porta de onde veio o pacote
            );

            // 6. Simulação de perda baseada no argumento args[1]
            if (Math.random() >= probPerdaSessao) {
                socket.send(ackPacketToSend);
                System.out.println("Enviado ACK para seq " + (expectedseqnum - 1));
            } else {
                System.out.println("[SIMULAÇÃO] ACK " + (expectedseqnum - 1) + " perdido de propósito!");
            }
        }

        fos.close();
        System.out.println("-------------- TRANSFERÊNCIA CONCLUÍDA ----------------");
    }
}