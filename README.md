Trabalho Final: Implementação do Protocolo Go-Back-N (GBN) em Java via UDP

Este repositório contém a implementação do protocolo Go-Back-N (GBN) utilizando exclusivamente sockets UDP, desenvolvido como Trabalho Final da disciplina de Redes de Computadores da Universidade Federal de Alfenas (UNIFAL-MG).

O sistema é composto por dois módulos independentes (Emissor e Receptor) que simulam a transferência confiável de um arquivo arbitrário.

## 📌 Funcionalidades e Requisitos Atendidos

*
**Comunicação UDP:** Utilização exclusiva de sockets UDP (`DatagramSocket` e `DatagramPacket`) para a comunicação entre cliente e servidor.


*
**Transferência de Arquivos Binários:** Suporte à transferência correta de arquivos como imagens, PDFs e executáveis.


*
**Lógica GBN:** Implementação fiel às Máquinas de Estados Finitos (FSMs) do protocolo, incluindo controle de tamanho de janela de transmissão (N), temporizador (timeout) e retransmissões.


*
**Simulação de Perdas:** Capacidade de descartar pacotes recebidos corretamente e em ordem, simulando perdas na rede com base em uma probabilidade configurável.


*
**Estatísticas de Rede:** Exibição em tempo real e ao final da execução de métricas como número de pacotes enviados, ACKs recebidos, retransmissões e taxa de throughput.


*
**Verificação de Integridade:** Comparação do hash (MD5/SHA-1) do arquivo original com o recebido para garantir a integridade dos dados (Requisito Desejável).



## 🛠️ Tecnologias Utilizadas

*
**Linguagem:** Java (JDK padrão, sem frameworks externos).


*
**Estruturas principais:** `DatagramSocket`, threads concorrentes/`ScheduledExecutorService` (para o temporizador) e `ByteBuffer` para serialização de cabeçalhos.



🚀 Como Compilar e Executar

Certifique-se de ter o Java Development Kit (JDK) instalado em sua máquina.

### Compilação

Abra o terminal na raiz do projeto e compile os arquivos Java:

```bash
javac Emissor.java Receptor.java

```

Execução

O programa deve ser executado via linha de comando, passando os parâmetros necessários. A simulação inicial é recomendada com probabilidade de perda em `0.0` para validar a transferência.

**1. Iniciando o Receptor:**

```bash
java Receptor <porta_local> <probabilidade_perda>

```

Exemplo (Porta 8080, 10% de perda ): `java Receptor 8080 0.1`

**2. Iniciando o Emissor:**

```bash
java Emissor <ip_destino> <porta_destino> <arquivo_origem> <tamanho_janela_N>

```

Exemplo (Janela de tamanho 5, arquivo de 1MB ): `java Emissor 127.0.0.1 8080 relatorio.pdf 5`

📊 Estrutura do Projeto

*
`Emissor.java`: Lógica de envio de dados, controle de janela (buffer circular), temporizador e processamento de ACKs.


*
`Receptor.java`: Lógica de recebimento, verificação de número de sequência esperado, envio de ACKs cumulativos e simulação de perda.


*
`Relatorio_Tecnico.pdf`: Relatório detalhando decisões de projeto, testes realizados e análise do impacto da variação da janela *N* e probabilidades de erro no tempo de transferência.



## 👩‍💻 Autoria

**Flávia Marcella Gonçalves Moreira** Bacharelado em Ciência da Computação – Instituto de Ciências Exatas (ICEx)
**Renan Catini Amaral** Bacharelado em Ciência da Computação - Instituto de Ciências Exatas (ICEx)

Universidade Federal de Alfenas (UNIFAL-MG)

---

*Baseado no livro "Redes de Computadores: Uma Abordagem Top-Down" (Kurose & Ross, 8ª ed., Cap. 3)*.