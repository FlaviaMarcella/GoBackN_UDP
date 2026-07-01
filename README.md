# Implementação do Protocolo Go-Back-N (GBN) em Java via UDP

Este repositório contém a implementação do protocolo Go-Back-N (GBN) utilizando exclusivamente sockets UDP, desenvolvido como Trabalho Final da disciplina de Redes de Computadores da Universidade Federal de Alfenas (UNIFAL-MG).

O sistema é composto por dois módulos independentes (Emissor e Receptor) que simulam a transferência confiável de um
arquivo arbitrário, garantindo a entrega íntegra mesmo em redes instáveis.

## 📌 Funcionalidades e Requisitos Atendidos

* **Comunicação UDP:** Utilização exclusiva de sockets UDP (`DatagramSocket` e `DatagramPacket`) para a comunicação entre cliente e servidor.
* **Transferência de Arquivos Binários:** Suporte à transferência correta de qualquer tipo de arquivo (imagens, PDFs,
  documentos, executáveis, etc).
* **Lógica GBN:** Implementação fiel às Máquinas de Estados Finitos (FSMs) do protocolo, incluindo controle de tamanho
  de janela de transmissão dinâmico (N), buffer circular, temporizador (timeout) e retransmissões automáticas.
* **Simulação de Perdas:** Capacidade de descartar pacotes recebidos corretamente e em ordem, simulando perdas na rede com base em uma probabilidade configurável.
* **Handshake Dinâmico:** Envio de parâmetros iniciais via pacote de controle para que o receptor recrie a estrutura de
  diretórios e o arquivo dinamicamente.
* **Estatísticas de Rede:** Exibição em tempo real do tráfego de rede, métricas de envio, recepção de ACKs e
  retransmissões no terminal.

## 🛠️ Tecnologias Utilizadas

* **Linguagem:** Java (JDK padrão, sem frameworks externos).
* **Estruturas principais:** `DatagramSocket`, Threads Concorrentes (para o recebimento paralelo de ACKs), `Timer`/
  `TimerTask` (para estourar timeouts assíncronos) e `ByteBuffer` para manipulação e alocação de cabeçalhos binários.

---

## 🚀 Como Compilar e Executar

Certifique-se de ter o Java Development Kit (JDK) instalado em sua máquina. Os comandos abaixo devem ser executados a
partir do diretório raiz do código-fonte (geralmente a pasta `java` ou `src/main/java`).

### 1. Compilação

Abra o terminal na raiz do projeto e compile os arquivos respeitando a estrutura do pacote:

No Windows:
```bash
javac org\unifal\flavia\renan\*.java
```

No Linux/macOS:

```bash
javac org/unifal/flavia/renan/*.java
```

### 2. Execução

O sistema exige a inicialização do Receptor antes do Emissor. Recomendamos usar a probabilidade `0.0` para
transferências limpas e valores como `0.1` (10%) para visualizar o Go-Back-N retransmitindo dados em ação.

**Terminal 1 (Iniciando o Receptor):**

```bash
java org.unifal.flavia.renan.Receiver <porta_local>
```

> **Exemplo:** `java org.unifal.flavia.renan.Receiver 5000`
>
> **Observação:** o Receptor recebe apenas a porta em que deve escutar. A probabilidade de perda,
> o caminho de destino do arquivo e o tamanho/hash do arquivo são enviados pelo **Emissor** no
> pacote de *handshake* inicial (tipo 2), e não via linha de comando do Receptor.

**Terminal 2 (Iniciando o Emissor):**

```bash
java org.unifal.flavia.renan.Sender <arquivo_origem> <ip_destino>:<diretorio_destino> <tamanho_janela_N> <prob_perda>
```

> **Exemplo:** `java org.unifal.flavia.renan.Sender relatorio.pdf 127.0.0.1:Recebidos/ 5 0.1`
> *(Nota: O emissor está configurado para se conectar à porta 5000 do destino).*

---

## 📊 Estrutura do Projeto

* `Sender.java`: Lógica de leitura de arquivos, empacotamento (`ByteBuffer`), envio de dados, controle de janela (buffer
  circular), temporizador assíncrono e thread de processamento de ACKs.
* `Receiver.java`: Lógica de recebimento, extração de cabeçalhos, manipulação do Sistema Operacional para criação de
  diretórios, gravação em disco (`FileOutputStream`), verificação de sequência, envio de ACKs cumulativos e simulação de
  perda de rede.
* `Relatorio_Tecnico.pdf`: Relatório detalhando decisões de projeto, testes realizados e análise do impacto da variação da janela *N* e probabilidades de erro no tempo de transferência.

## 👩‍💻 Autoria

* **Flávia Marcella Gonçalves Moreira** - Bacharelado em Ciência da Computação (ICEx)
* **Renan Catini Amaral** - Bacharelado em Ciência da Computação (ICEx)

*Universidade Federal de Alfenas (UNIFAL-MG)*

---

*Baseado no livro "Redes de Computadores: Uma Abordagem Top-Down" (Kurose & Ross, 8ª ed., Cap. 3).*