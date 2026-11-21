# EasyJoy — design

Celular Android vira controle de Xbox ou touchpad/mouse do PC Windows, pelo Wi-Fi de casa.

## Partes

- **`android/`** — app Android (Kotlin). Menu inicial com a conexão e três modos: **Completo**, **Simples**, **Mouse**.
- **`pc/`** — programa Windows (C# .NET 8, WinForms, ícone na bandeja). Recebe os pacotes, cria um controle Xbox 360 virtual via driver ViGEmBus (biblioteca `Nefarius.ViGEm.Client`) e move o mouse/teclado via `SendInput`.

## Objetivos

- Jogos e emuladores enxergam um controle Xbox comum, sem configurar nada.
- Abrir o app, tocar no PC encontrado, escolher o modo. Sem digitar IP (IP manual existe só como reserva).
- Latência baixa: UDP na rede local.

## Fora do escopo

Layout personalizável, jogar pela internet, iOS, macOS/Linux, autenticação/pareamento (rede local de casa).

## Protocolo (UDP, porta 47820, little-endian)

Todo pacote começa com o cabeçalho de 4 bytes: `'E' 'J' 0x01 <tipo>`. Pacotes com cabeçalho inválido são ignorados.

| Tipo | Direção | Corpo (depois do cabeçalho) |
|---|---|---|
| `0x01` DISCOVER | celular → broadcast | vazio |
| `0x02` ANNOUNCE | PC → celular (unicast, resposta ao DISCOVER) | nome do PC, UTF-8 |
| `0x05` PING | celular → PC, a cada 1 s em qualquer tela conectada | UTF-8 nome do aparelho |
| `0x06` PONG | PC → celular | `u8 status`: 0 = ok, 1 = driver de controle ausente (mouse funciona, controle não) |
| `0x10` GAMEPAD | celular → PC | `u16 seq`, `u16 buttons`, `u8 lt`, `u8 rt`, `i16 lx`, `i16 ly`, `i16 rx`, `i16 ry` (18 bytes total) |
| `0x20` MOUSE_MOVE | celular → PC | `i16 dx`, `i16 dy` (pixels, já com sensibilidade aplicada no celular) |
| `0x21` MOUSE_BUTTON | celular → PC | `u16 eventId`, `u8 button` (0 esq, 1 dir, 2 meio), `u8 action` (0 solta, 1 aperta, 2 clique) |
| `0x22` SCROLL | celular → PC | `i16 vertical`, `i16 horizontal` (unidades de roda do Windows: 120 = um "dente"; positivo = rolar pra cima / direita) |
| `0x23` TEXT | celular → PC | `u16 eventId`, texto UTF-8 |
| `0x24` KEY | celular → PC | `u16 eventId`, `u8 virtualKey` (VK do Windows: 0x08 Backspace, 0x0D Enter, 0x1B Esc, 0x09 Tab, 0x25–0x28 setas) |
| `0x30` BYE | celular → PC | vazio — saiu do modo controle; PC desconecta o controle virtual desse celular |

Regras:

- **Gamepad**: o celular manda o estado inteiro a cada mudança e também a cada 50 ms. `buttons` usa as máscaras do XInput: Cima 0x0001, Baixo 0x0002, Esq 0x0004, Dir 0x0008, Start 0x0010, Back 0x0020, L3 0x0040, R3 0x0080, LB 0x0100, RB 0x0200, Guide 0x0400, A 0x1000, B 0x2000, X 0x4000, Y 0x8000. Eixos −32768..32767, **Y positivo = para cima**. O PC descarta pacote com `seq` mais antigo (comparação com wraparound: `(short)(seq - last) <= 0` → descarta, exceto se passou >1 s desde o último).
- **Eventos únicos** (`0x21`, `0x23`, `0x24`) são enviados **3 vezes** com o mesmo `eventId`; o PC ignora `eventId` repetido (guarda os últimos 64 por celular).
- O PC cria **um controle virtual por celular** (chave = IP) no primeiro GAMEPAD, e o remove após `BYE` ou 3 s sem nenhum pacote desse IP (antes de remover, zera o estado).
- O celular considera a conexão perdida após 3 s sem PONG e mostra isso na tela.

## App Android

- Kotlin, Jetpack Compose para menu/configurações; **as telas de controle e o touchpad são `View` customizadas desenhadas em Canvas** (multitoque confiável, dedo pode deslizar de um botão pro outro).
- **Menu com 3 abas na barra de baixo**: *Layouts* (layouts salvos: jogar, editar, apagar; criar a partir de Completo/Simples/Volante, que abre direto no editor), *Controles* (padrão: conexão e os modos), *Ajustes* (controle, volante, mouse).
- **Aba Controles**: lista de PCs encontrados (DISCOVER a cada 2 s pra 255.255.255.255 e pro broadcast da sub-rede), toque para conectar; campo de IP manual; lembra o último PC e reconecta sozinho. Depois de conectado: três cartões grandes (Completo / Simples / Mouse), ajustes (vibração liga/desliga, sensibilidade do mouse).
- **Completo** (paisagem, tela cheia): LT/LB à esquerda no topo, RB/RT à direita no topo; analógico esquerdo e direcional à esquerda; ABXY à direita (Y cima, X esq, B dir, A baixo, cores Xbox); analógico direito embaixo à direita; Back/Start no centro; botão Menu pequeno no topo central volta ao menu. Gatilhos digitais (0 ou 255).
- **Simples** (paisagem): um analógico grande à esquerda que manda o analógico esquerdo **e** o direcional (quando passa de 50% numa direção); ABXY grandes; LB/RB; Back/Start.
- **Analógico**: zona de toque maior que o desenho; zona morta 10%; o dedo que começou no analógico fica com ele até soltar.
- **Mouse** (qualquer orientação): área de touchpad — arrastar 1 dedo move; toque rápido = clique esquerdo; toque com 2 dedos = clique direito; arrastar 2 dedos = rolagem; tocar e segurar ~0,4 s e arrastar = arrastar segurando o botão esquerdo. Barra embaixo com botões Esquerdo / Direito, botão Teclado (abre o teclado do celular; letras viram TEXT, apagar vira KEY Backspace, Enter vira KEY Enter).
- **Home** (Guide 0x0400) nos dois modos, acima de Back/Start.
- **Tamanho dos botões** (Ajustes, 80–120%, passos de 5%): tamanhos escalam; posições só se deslocam o necessário para não sair da tela nem encostar no vizinho; LB/RB/LT/RT crescem só na largura. Um toque que cai no direcional ou num botão nunca captura o analógico vizinho.
- **Volante** (modo próprio, ou ligado em qualquer layout pelo editor): `Tilt.kt` lê a gravidade ao longo do eixo comprido do celular deitado (`asin(gy/g)`, sinal invertido em ROTATION_270), então funciona tanto girando como volante quanto levantando um lado. Vira o **eixo X do analógico esquerdo** (só quando o polegar não está no analógico). Zona morta 2°; "girar N° = direção toda" em Ajustes (15–90°, padrão 45°); "Centralizar" grava o ângulo atual como reto. Sem leitura nova do sensor, a direção é zero. O layout Volante tem pedais digitais Freio (LT) / Acelerar (RT).
- **Editor de layout** (botão "Editar" no controle): selecionar, arrastar, −/+ tamanho, ocultar/mostrar, ligar volante, voltar ao padrão, cancelar, salvar com nome. Layouts salvos (`Layouts.kt`) ficam em JSON no SharedPreferences: base (completo/simples/volante), volante ligado, e cada controle com centro em fração da largura/altura e tamanho em unidades da altura. Controles não podem ser arrastados para baixo da barra de edição.
- Vibração curta ao apertar botão (configurável). Tela fica ligada nos modos de controle.
- minSdk 26.

## Programa do PC

- Janela pequena "EasyJoy": status do driver (ok / ausente + botão que abre a página oficial do ViGEmBus), nome e IP(s) do PC, lista de celulares conectados e modo de cada um, opção "Iniciar com o Windows" (chave `HKCU\...\Run`). Fechar a janela minimiza para a bandeja; sair pelo menu da bandeja.
- Escuta UDP 47820 em todas as interfaces. Na primeira execução o Windows pergunta sobre o firewall: permitir em rede privada.
- Distribuído como `EasyJoy.exe` único (self-contained, win-x64).

## Decisões tomadas pelo construtor

Depois da revisão (Codex): o PC só considera `eventId` repetido dentro de 2 s (reabrir o app zera o contador sem perder cliques); ignora evento de botão mais antigo que o último do mesmo botão; solta um botão do mouse só quando nenhum celular o segura; solta botões presos após 15 s sem atividade de mouse. O celular manda estado neutro + BYE ao sair da tela de controle e só fecha o socket depois de enviar o que estava na fila; no touchpad, só o dedo dono do arrasto encerra o arrasto.

Porta 47820; UDP sem autenticação (risco aceito: qualquer aparelho na mesma rede pode mandar comandos enquanto o programa está aberto); gatilhos digitais; analógico do modo Simples também aciona o direcional; sensibilidade do mouse aplicada no celular.
