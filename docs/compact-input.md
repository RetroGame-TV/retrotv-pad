# Entrada compacta sem depender de HID

Pad 0.3.6-dev negocia `inputBinary=1` nos metadados da TV e passa a enviar o estado completo em 9 bytes, em Bluetooth RFCOMM e Wi-Fi. Com TV antiga, continua enviando as linhas existentes. O estado inicial pode chegar em texto; a TV 0.7.5 aceita texto e binário misturados no mesmo fluxo.

Formato: marcador 0xF1, três bytes little endian de botões, um de direcional e quatro eixos com sinal (-127 a 127). Os bits, combinações, Home/Voltar e quantização não mudam. O sender descarta estados equivalentes após quantizar e mantém todas as mudanças digitais em FIFO; os analógicos pendentes continuam usando a atualização mais recente. Não há um novo timer de envio. Write/flush imediatos e TCP_NODELAY no Wi-Fi já existiam e são mantidos.

Não depende de HID, indisponível no moto g55 do usuário. Não introduz BLE nem mistura duas conexões simultâneas sem deduplicação. As rotas continuam alternativas. Bluetooth físico e redução de milissegundos no rádio não foram medidos.

Validação: 47 testes JVM, 24 testes do runner Android (incluindo caso opcional Delta que não carrega referência sem o argumento correspondente), mais 1 teste opt-in com Street Fighter II ativo no aplicativo TV atualizado. O sender real negocia e envia direções, diagonais, seis golpes, quarto de círculo e rajada de 12 mil estados; a conexão permanece ativa. A contagem de todas as transições é conferida separadamente no teste do receptor da TV. Foram preservados controles realistas/opcionais, customização, multitoque, AUTO e volume da TV.

Microbenchmark Android virtual, mediana de sete rodadas alternadas de 5.000 estados após aquecimento: serialização texto→bytes 13.067 ns/estado, binário 589 ns/estado. Custo de CPU apenas, sem rádio/rede/TV. Sem limiar de desempenho no teste.

O `CompactInputAndroidTest` usa servidor TCP loopback e confere estado inicial legado, negociação, todos os bits de botão, direções/combinações, solturas e supressão de dez mil movimentos equivalentes. `LiveTvInputTest` exige `liveTv=1`, `tvHost` e `tvToken` de um cenário de TV virtual já autorizado, com jogo SNES ativo. Nunca publicar token real ou ROM de teste. As imagens são gravadas apenas na pasta do app de teste.

Detalhes do receptor, layout do long JNI, falha por saturação e limites: [documentação da TV](https://github.com/RetroGame-TV/retrotv-lite/blob/codex/late-input-and-button-edges/docs/compact-input.md).
