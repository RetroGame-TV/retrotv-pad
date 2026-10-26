> As seções até a 0.3.4 descrevem o período em que o Pad usava código do Bluke. A partir de "Projeto independente · 0.3.5-dev" ele não usa mais.

# RetroTV Pad — progresso
## Marco 1 — controle e layouts
Implementados: módulo Kotlin/Canvas para Android9+, HID do Bluke, seis skins originais responsivas, multitouch, dois analógicos PS1, pausa START+SELECT, conexão a dispositivos pareados e pedido de visibilidade.
Validado: build, 37 testes JVM e 3 testes Android. Capturas do renderer em paisagem/retrato. Pareamento e latência físicos pendentes.

## Marco 2 — skins e integração
Leitor Delta independente com ZIP/PDF/PNG/JPEG, subset iPhone standard/edgeToEdge, limites e validação de caminhos. Teste explícito da skin SNES padrão original passou em portrait/landscape.
TV0.4 publica metadados por RFCOMM seguro com opt-in; app troca o layout quando AUTO está ligado. Protocolo e telas testados; ligação Bluetooth ponta a ponta pendente de celular/TV reais.

Bluke bcf4f1492b51bcbb97fc7b792f73ad6bed75c759 preservado em app/; os três arquivos HID/input e dois conjuntos de testes usados permanecem no módulo retropad. AGPLv3 e fontes correspondentes acompanham a entrega. Não há publicação remota.
## Marco 3 — controle em tela cheia · 0.2.0-dev
Uso sempre em paisagem (`sensorLandscape`), controle ocupando a tela inteira. A barra de abas saiu: um selo discreto no topo mostra console e conexão e abre o painel de layout, AUTO, conexão e skins. Medidas em dp (botões de ~62 dp, analógicos de 96 dp, gatilhos de 50 dp de altura), áreas de toque maiores que o desenho e escolha do controle mais próximo quando as áreas se sobrepõem.

Peças vetoriais em `PadArt.kt` recriadas a partir da anatomia das skins abertas do Delta (sem embutir arte delas): direcional com setas em relevo, botões convexos com letra gravada, poços e trilhos, pílulas inclinadas, analógicos côncavos e alavanca de arcade.

Modo Personalizar (painel do selo): arraste um grupo para movê-lo (posição salva como fração da tela) ou toque num grupo — direcional, botões, gatilhos, Start/Select ou analógicos — e ajuste de 70% a 140% com − e + ou com pinça; tamanho salvo por console, ↺ restaura posição e tamanho e Voltar conclui. O grupo é redimensionado em torno do próprio centro e empurrado de volta para dentro da tela se passar da borda.

Executado: 42 testes JVM (5 de escala, arrasto e limites), lint 0 erros/12 avisos, AndroidJUnitRunner 4/4 no Android 11 virtual (teste multitoque fixa o layout SNES pelo extra de depuração) e capturas dos seis layouts e do painel em 2340×1080. Toque físico, latência e Bluetooth reais continuam pendentes.

### Conexão Bluetooth
Conectar abre: Procurar TV próxima (seletor de dispositivos do Android via Companion Device Manager, sem permissão de localização nem de busca), TVs já pareadas (a última usada primeiro) e Parear pela TV (celular visível por 3 min). Escolher uma TV não pareada inicia o pareamento pelo celular; ao concluir, conecta sozinho, com até três tentativas. Ligar o Bluetooth depois de abrir o app agora prepara o controle. Verificado no Android 11 virtual: seletor aberto, dispositivo devolvido ao app, pareamento iniciado (BOND_BONDING) e recusa tratada. Pareamento e conexão com TV física continuam pendentes.

### Registro HID e diagnóstico
Causas encontradas para "HID não respondeu": (1) a mensagem de tempo esgotado sobrescrevia a causa real (registro recusado) e (2) a sessão HID era derrubada e recriada imediatamente (botão Conectar, retorno de telas do sistema), e o Android recusa um novo registro enquanto o anterior não foi liberado, pois só um app pode ser controle HID por vez. Agora o registro tenta até 4 vezes liberando o anterior, cada etapa tem mensagem própria e a sessão existente é reaproveitada. Mais opções > Diagnóstico Bluetooth mostra aparelho, versão, permissões, se o serviço HID abriu, tentativas e eventos com tempo, com botão Copiar. Painel do selo agora se adapta a telas baixas. Celular físico ainda não testado: se "Serviço HID aberto: não", o perfil está desativado pelo fabricante do aparelho.

## Marco 4 — Wi-Fi, Google TV e HID seletivo · 0.2.1-dev
Rotas, nesta ordem: HID (só se o celular realmente oferece o perfil), canal RetroTV (Bluetooth RFCOMM ou Wi-Fi com código da TV) e controle remoto do Google TV. HID é desligado sozinho quando o serviço do Android nunca abre (ex.: moto g55 5G, Android 16, perfil desativado pelo fabricante); Mais opções > Diagnóstico tem "Testar HID de novo". Conectar lista TVs RetroTV e Google TV achadas no Wi-Fi (NSD), TVs pareadas, endereço manual e busca Bluetooth.

Canal RetroTV: envia o estado do controle ao RetroTV Lite, que cria um controle virtual. Manda um estado em repouso ao conectar e `K` a cada 5 s para não cair parado.

Google TV (protocolo Android TV Remote v2, navegação: direcional, OK, Voltar, Menu): chave RSA no AndroidKeyStore com permissão de assinatura e RSA cru (exigido pelo TLS com RSA-PSS) e certificado próprio X.509 v3 SHA256withRSA CA:true; certificado da TV fixado no pareamento.

Executado: 47 testes JVM (inclui certificado e segredo de pareamento), lint 0 erros, AndroidJUnitRunner 4/4. Ponta a ponta em dois emuladores: canal RetroTV por Wi-Fi funcionando (ver progresso da TV). Google TV contra o Android TV Remote Service 5.2 do emulador: pareamento completo (código na TV, aceito, "paired successfully", certificado gravado na TV); o canal de comandos (porta 6466) é recusado no handshake TLS pelo serviço do emulador, também para cliente sem certificado, sem log do motivo. Envio de teclas ao Google TV não foi verificado; precisa de TV real.

## Ajustes de latência · 0.2.2-dev
Com um link Wi-Fi ativo (canal RetroTV por Wi-Fi ou Google TV), o Pad segura uma trava de Wi-Fi de baixa latência (`WIFI_MODE_FULL_LOW_LATENCY`; alto desempenho no Android 9), solta ao sair do app. Mudanças só de analógico vão no máximo a cada 8 ms, sempre com o valor mais recente; botões e direcional continuam saindo na hora. O selo mostra "console · conectar" quando desconectado e "console · AUTO" quando conectado, sem cortar o texto.

Executado: 48 testes JVM (inclui ritmo do analógico), lint 0 erros; trava confirmada ativa (`dumpsys wifi`, modo 4) no emulador conectado.

## Refinos de interface · 0.2.3-dev
Status do painel neutro ("Pronto para conectar à TV") em vez de "Bluetooth pronto", já que a conexão pode ser por Wi-Fi. O direcional do PS1 usa o mesmo centro escuro côncavo dos outros consoles, sem o círculo claro grande.

Executado: 48 testes JVM, lint 0 erros, captura do layout PS1 no emulador.

## Controle da TV e botão Início · 0.3.0-dev
Novo layout "TV" no painel: anel de setas com OK no centro, Voltar, Início e Menu, para navegar pela galeria sem o controle de jogo. Os layouts de jogo ganharam um botão Início (casa) na fileira de sistema. Início usa o bit 16, o mesmo índice do Guide no relatório HID; no Google TV vira a tecla Home. O painel passou a ter sete layouts.

Executado: 49 testes JVM (inclui mapeamento do controle da TV e do Início), lint 0 erros; capturas do layout TV e do NES com Início; com a TV virtual, OK abriu a demo, Menu abriu Ajustes e Início voltou a Início e pausou o jogo.

## Retornar · 0.3.1-dev
Botão Retornar (↩) nos layouts de jogo, à direita da fileira de sistema, espelhando o Início; no controle da TV o rótulo passou de "Voltar" para "Retornar". Usa o bit 17 (nunca um botão de jogo), por isso funciona também no meio da partida. No Google TV vira a tecla Voltar. Executado: 49 testes JVM, lint 0 erros, teste com a TV virtual.

## Teste na Philco e nomes de conexão · 0.3.2-dev
Na Philco real o celular conectou pelo controle remoto do Google TV (porta 6466): primeira validação desse modo em aparelho físico; setas, OK e voltar navegam. Botões de jogo e AUTO não funcionavam porque o canal do RetroTV estava desligado na TV; esse modo só leva teclas de navegação. O menu Conectar agora diz "Jogar (RetroTV)" e "Só menus (controle remoto da TV)", com as opções de jogar sempre acima, e o status do controle remoto explica como ativar o modo de jogo. 49 testes JVM, lint 0 erros.

## Endereço IPv4 na descoberta · 0.3.3-dev
A descoberta de TVs na rede passa a preferir o endereço IPv4: no Android 14+ a lista pode trazer primeiro um IPv6 local que um socket comum não alcança. 49 testes JVM, lint 0 erros.

## Conexão automática ao canal de jogo · 0.3.4-dev
Ao abrir, o Pad procura o RetroTV na rede (NSD `_retrotv._tcp.`) e entra sozinho no canal de jogo, uma vez por TV enquanto o app estiver aberto; se o celular já pareou, não pede código. Evita ficar só no modo "Só menus", que não tem botões de jogo, AUTO nem direcional segurado. 49 testes JVM, lint 0 erros. Não testado entre emuladores (cada um tem rede própria e o NSD não atravessa); validar com o celular real.

## Projeto independente · 0.3.5-dev
O RetroTV Pad deixa de ser derivado do Bluke. O Bluke fica citado como inspiração.
- **Removido:** o app Bluke original (`app/`), os materiais de loja, a política de privacidade e o changelog do Bluke, e os três arquivos dele que o Pad usava (descritor HID, codificador de report e direção do direcional), com os testes correspondentes.
- **Controle Bluetooth HID reescrito do zero** (`HidGamepad.kt`), a partir da especificação USB HID 1.11:
  - um report (ID 3, 8 bytes) com 16 botões, hat de 4 bits e quatro eixos de 8 bits;
  - os botões caem na ordem de controle do Android (A, B, X, Y, L1, R1, L2, R2, Select, Start, MODE, L3, R3);
  - Início vai como MODE;
  - subclasse Bluetooth de gamepad.
- **Direcional por toque:** função própria (`dpadBits`, setores de 45°).
- **Licença:** passa a ser GNU GPL v3 ou posterior. As versões até a 0.3.4 incluíam código do Bluke e seguem AGPL-3.0.
- **Tela de créditos:** mostra o novo NOTICES e a GPL-3.0.
