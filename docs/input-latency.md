# Envio de entrada e latência

## Mudanças

O canal de jogo **RetroTV por Wi-Fi ou Bluetooth RFCOMM** passa a enviar os analógicos sem a espera artificial de até 8 ms de `StickPacer`. O trabalho é entregue imediatamente ao escritor quando ele está livre. Se estiver ocupado, movimentos consecutivos com os mesmos botões/direcional são substituídos pelo estado mais recente. Não há um temporizador por movimento nem uma tarefa por evento acumulada no executor.

`PadStateSender` mantém uma fila de até 32 estados por conexão. Mudanças de botões e direcional ficam em ordem; somente os eixos entre essas mudanças podem ser consolidados. Saturação, falha de escrita ou rejeição do executor fecham a conexão, em vez de descartar silenciosamente uma soltura. Fechar uma conexão descarta sua fila; os estados antigos não são reproduzidos na conexão seguinte. O estado inicial entra na fila antes de a conexão ser disponibilizada ao toque.

A formatação do protocolo usa conversões diretas, independentes do idioma do celular, e ocorre no escritor, fora da entrega de toque. Os mesmos campos, máscaras, limites e arredondamento dos eixos são mantidos. `TCP_NODELAY` e o bloqueio Wi-Fi de baixa latência já existiam e foram preservados.

Durante o uso do controle, a View solicita `requestUnbufferedDispatch` no início do gesto, permitindo que o Android entregue movimentos sem agrupá-los para o próximo quadro local. O editor e a folha de opções mantêm a entrega normal. O comando também é publicado antes da chamada de vibração. O desenho continua sob demanda; os modos clássico/realista e as áreas de toque não mudam. Referência: [entrega de movimentos sem agrupamento no Android](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features). Essa solicitação não garante melhoria em todos os aparelhos; o efeito no agendamento, no desenho e no consumo depende do dispositivo.

**Bluetooth HID:** mantém seu escritor e sua fila próprios, que já enviavam sem `StickPacer`. Recebe as mudanças comuns da entrega de toque e da ordem da vibração. O protocolo Google TV continua destinado a menus/volume; não substitui o canal de jogo.

## Direcional interrompido pelo AUTO

O contexto enviado pela TV se repete a cada segundo. Antes desta correção, `PadActivity.metadata` chamava `select` em todos os pacotes reconhecidos enquanto AUTO estivesse ligado, inclusive quando o console não mudava. `select` liberava o estado, apagava os contatos e reconstruía o layout. Assim, segurar uma direção dentro do jogo podia ser interrompido pela próxima atualização de contexto, sem o usuário levantar o dedo. Os botões e analógicos segurados também eram afetados; um novo MOVE não recuperava o contato apagado.

AUTO agora só seleciona um layout quando o console realmente muda. Título/modo novos continuam sendo atualizados, e pacotes completamente repetidos não solicitam um novo desenho. Uma troca real de console mantém a liberação de segurança; modo manual e cancelamento continuam funcionando. A correção vale para RetroTV por Bluetooth RFCOMM e Wi-Fi e não depende de atualizar o APK da TV.

Reprodução em Android antes da correção: `AutoMetadataAndroidTest.repeatedAutoPacketsKeepTheDpadAndSecondFingerHeld` falhou no primeiro pacote de contexto, passando de `buttons=1, dpad=8` para `buttons=0, dpad=0`. O outro teste confirmou que uma troca real de console continuava liberando o estado. A regressão adicionada cobre oito atualizações repetidas, mudança só do título, soltura independente, cancelamento, modo manual e PS1 com dois analógicos mais um botão segurados.

Depois da correção, a suíte completa passou com **44 testes JVM e 21 testes Android** no Android 11/API 30 x86 virtual. Build debug/APK de testes e lint também passaram, com zero erros/12 avisos. Isso confirma a correção da falha reproduzida no Android; a repetição com o celular e a TV físicos por Bluetooth continua pendente.

## Próximas oportunidades no software da TV

Auditoria do RetroTV Lite na revisão [43f35e2](https://github.com/RetroGame-TV/retrotv-lite/tree/43f35e2d46e1894031d16746beca427a13085361), conferida com a branch principal. **As propostas abaixo ainda não foram implementadas nesta alteração do Pad.**

1. **Evitar reproduzir movimentos antigos na TV.** `PhoneInputQueue` guarda até quatro estados e entrega um por chamada de polling; o laço principal chama esse polling uma vez por quadro. Uma rajada pode, portanto, fazer um movimento esperar atrás de estados anteriores. Separar o estado mais recente dos eixos das transições digitais permitiria remover esse atraso sem apagar toques curtos. É preciso testar explicitamente pressão/soltura, diagonais, jogador 2 e desconexão. Código: [ponte do celular](https://github.com/RetroGame-TV/retrotv-lite/blob/43f35e2d46e1894031d16746beca427a13085361/app/src/main/java/org/retrotv/lite/PhoneControllerBridge.kt).
2. **Ler a entrada mais perto da execução do quadro.** Hoje o laço coleta o telefone e preenche os botões/eixos antes de esperar o instante do próximo quadro. Reposicionar essa coleta depois da espera, ou na consulta de entrada do núcleo, reduziria a idade do estado usado pelo jogo. Os eventos de menu, a atualização do joystick virtual e os botões curtos precisam continuar corretos: a [SDL exige uma atualização para aplicar estados virtuais](https://wiki.libsdl.org/SDL2/SDL_JoystickSetVirtualButton). Código: [laço do jogo](https://github.com/RetroGame-TV/retrotv-lite/blob/43f35e2d46e1894031d16746beca427a13085361/native/main.cpp).
3. **Medir até o consumo pelo jogo.** Associar uma sequência ao comando e responder somente quando o quadro consumir esse estado permitiria medir toque → confirmação do consumo. Esse tempo inclui a volta da confirmação; não deve ser apresentado como latência de ida nem como tempo até o pixel da TV. A medição pode ficar opcional/local e amostrada para não criar tráfego contínuo desnecessário.

Esses são pontos encontrados no caminho real do código. Nenhum ganho em milissegundos ou resultado em Bluetooth físico foi atribuído às propostas.

## Validação

- Seis testes JVM de `PadStateSender`: envio imediato sem relógio/temporizador, consolidação de 1.000 movimentos, preservação de transições, saturação, conexão substituída, atualização durante escrita e falhas de escrita/executor.
- Protocolo conferido em localidade árabe: continua emitindo dígitos ASCII.
- Teste Android com `RetroTvLink` real e servidor TCP em `127.0.0.1`: handshake, estado inicial, movimentos isolados, 500 movimentos seguidos de pressionar/soltar e eventos da View incluindo cancelamento. Só o destino é simulado. Isso valida o caminho de envio; não mede rede sem fio ou resposta de uma TV.
- Microbenchmark informativo da serialização no Android virtual, com aquecimento, 256 estados e sete rodadas alternadas de 5.000 conversões por implementação. Compara a antiga formatação com `Formatter` à nova `PadWire.input`, verificando primeiro a igualdade dos resultados. Sem limiar de tempo que torne o teste dependente da velocidade do aparelho. Os números são publicados no resultado da instrumentação.

Na validação anterior à correção do AUTO em 27/10/2026, passaram **44 testes JVM e 18 testes Android**, build debug/APK de testes e lint com zero erros/12 avisos existentes nesta branch. Nessa execução no Android 11/API 30 x86 virtual, a mediana de serialização foi **24.394 ns/estado com Formatter e 1.977 ns/estado com PadWire** (cerca de 24,4 µs e 2,0 µs). Na execução completa após a correção do AUTO (21 testes Android), foram 12.905 e 974 ns/estado. São amostras de custo de CPU em emulador, sujeitas a variação entre execuções; o teste não usa esses valores como promessa ou critério de aprovação. A conexão TCP local passou em teste isolado e nas duas suítes completas.

Para repetir os testes Android, acrescente `org.retrotv.pad.PadLatencyAndroidTest` à lista de classes do [procedimento de validação](realistic-controls.md). Para executar somente a conexão local:

```sh
adb -s emulator-5558 shell am instrument -w -e class org.retrotv.pad.PadLatencyAndroidTest#touchAndAxisStatesReachTheRealTcpWriterInOrder org.retrotv.pad.test/androidx.test.runner.AndroidJUnitRunner
```

Não há medição de latência total toque → rede/Bluetooth → emulador da TV → tela em hardware físico. A remoção da espera de até 8 ms descreve o temporizador retirado do app, não uma redução garantida de 8 ms no jogo. O microbenchmark de formatação também não representa a latência total nem compara FPS dos modos visuais.
