> Registro de 24/10/2026 (0.1.0), quando o Pad ainda usava código do Bluke. Desde a 0.3.5 o projeto é independente; veja [progress-retrotv.md](progress-retrotv.md).

# Validação local — RetroTV Pad 0.1.0-dev

Data: 24/10/2026. Compilação em Windows/JDK21, Android SDK35; renderer Android 11 x86 virtual. Não houve celular ou TV de referência física conectados.

## Executado

- Gradle :retropad:assembleDebug :retropad:assembleDebugAndroidTest :retropad:testDebugUnitTest :retropad:lintDebug — BUILD SUCCESSFUL.
- 37 testes JVM passaram: 9 de descritor HID e 16 de reports herdados do Bluke, 9 de input/perfis/protocolo e 3 de fila de reports.
- Android PadAndroidTest: 3/3 passaram. Parser de skin em coordenadas reais com decode PNG, rejeição de caminhos inseguros/sistema não suportado/profundidade excessiva, eventos MotionEvent de dois dedos com IDs distintos, soltura independente e cancelamento.
- ReferenceDeltaTest com referenceSkin=1: 1/1 passou. Pacote original Standard.deltaskin do SNESDeltaCore ed3a432616bfabaa376c554f7d352958e5bfb13b, 18.098.369 bytes; representação portrait e landscape, PDFs renderizados pelo PdfRenderer Android, direcional e oito ou mais controles reconhecidos. A referência foi usada somente no emulador; suas imagens não acompanham APK/ZIP.
- Capturas do próprio aplicativo nos seis layouts. Viewport Android virtual 2160×1080 e 1080×2400; verificação adicional em 1080×1440 levou ao modo compacto sem tela decorativa para não sobrepor controles.
- Lint: zero erros, 13 avisos (sugestões de dependências, desenho, recursos/preview e texto de créditos). Não foram desabilitados checks para ocultar falhas.
- Sem permissão INTERNET, localização ou descoberta por BLUETOOTH_SCAN; pareamento por dispositivos já pareados/visibilidade temporária solicitada pelo usuário.
- Descritor e codificador Android do Bluke preservados. Fila limitada mantém transições de botão pressionado/solto, agrupa apenas movimento consecutivo dos analógicos e encerra conexão diante de saturação/falha persistente. Em segundo plano a Activity libera os comandos e encerra HID/RFCOMM.

Comandos Android: adb install -r; am instrument -w -e class org.retrotv.pad.PadAndroidTest org.retrotv.pad.test/androidx.test.runner.AndroidJUnitRunner. Teste de referência exige colocar o pacote externo em files/reference.deltaskin via run-as e executar ReferenceDeltaTest com referenceSkin=1. APK instrumentado e referência não são instalados no celular do usuário nesta entrega.

## Integração com a TV

RetroTV Lite 0.4: canal seguro RFCOMM opcional, UUID 8a421b90-d104-4ba2-8c72-50a34191ce01; opt-in em Ajustes > Controle pelo celular. Pacotes JSON UTF-8 com version=1, system, title e mode; limite de linha 2048 bytes. Somente aparelhos pareados, até dois clientes, encerramento no onStop. Publicação ocorre quando contexto muda; heartbeat de metadados 1s. O controle de jogo usa HID; não há comandos de execução no canal de metadados.

TV: build debug x86 e ARMv7 pessoal passou, 7 testes JVM e lint passaram. Android: 5/5 (4 de armazenamento + 1 do protocolo com UTF-8/escape/limites). CTest Windows: 3/3 (navegação/perfis, QuickNES/saves e VFS). Menu de ativação navegado por D-pad e capturado.

## Pendente de hardware

Pareamento HID celular–TV, dois canais Bluetooth simultâneos, troca automática ponta a ponta, reconexão em ROM OEM, latência e mapeamento SDL no dispositivo real. Os testes virtuais e de protocolo não comprovam esses itens. Não há promessa de compatibilidade universal.

Auto funciona com a integração RetroTV Lite 0.4; em outros emuladores é seleção manual. O importador é um subset do formato Delta, não executa Delta/iOS. Nenhum jogo/BIOS é incluído ou testado pelo app de controle. A variante da TV mantém as restrições pessoais e as limitações dos núcleos descritas no projeto RetroTV Lite.