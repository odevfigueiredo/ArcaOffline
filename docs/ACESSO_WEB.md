# Acesso web pela rede local

O painel web lê e gerencia o cofre que está aberto no celular. Use um computador, tablet ou outro celular na mesma rede Wi-Fi ou Ethernet. O acesso depende da rede local. Ela pode estar desconectada da internet.

## Conectar um navegador

1. Desbloqueie o Arca Offline no celular. Toque em **Acesso web**. Se aparecer mais de um endereço, escolha o da rede usada pelo outro dispositivo.
2. Toque em **Preparar acesso HTTPS** e depois em **Salvar certificado público**. Transfira `Arca-Offline-LAN.crt` por um meio em que você confia, como cabo USB. O arquivo contém o certificado público da instalação.
3. No outro dispositivo, confira o SHA-256 do arquivo com a impressão digital mostrada no Arca. No Windows, `Get-FileHash .\Arca-Offline-LAN.crt -Algorithm SHA256` mostra o mesmo valor sem os separadores.
4. Instale o certificado como autoridade confiável para sites nesse navegador ou dispositivo. No Firefox, pode usar a aba **Autoridades** do gerenciador de certificados. No Windows, o Edge pode usar as autoridades raiz confiáveis do usuário. No iOS, certificados instalados manualmente precisam de confiança explícita no sistema. Prefira um dispositivo próprio e remova a autoridade quando deixar de usar o acesso web. [Mozilla](https://support.mozilla.org/en-US/kb/setting-certificate-authorities-firefox) · [Microsoft](https://learn.microsoft.com/en-us/windows-server/identity/ad-cs/inspect-certificates-manage-certificate-stores) · [Apple](https://developer.apple.com/documentation/Network/creating-an-identity-for-local-network-tls)
5. No Arca, toque em **Ativar acesso HTTPS**. Abra o endereço mostrado no navegador e digite o código de pareamento. Se houver alerta de certificado, confira o endereço, o certificado importado e o relógio do aparelho antes de continuar.
6. Mantenha a tela **Acesso web** aberta. Encerre pelo navegador ou use **Encerrar acesso web** no celular quando terminar.

## O que o painel pode fazer

- Consultar e filtrar registros por categoria, conteúdo ou presença de anexo.
- Criar, editar e excluir registros. A alteração só é confirmada depois da gravação criptografada no celular.
- Consultar anexos existentes. Baixar um anexo salva uma cópia legível no destino escolhido pelo navegador.
- Baixar o cofre em um arquivo `.arca` protegido pela senha principal. A restauração, o envio de novos anexos e a troca de senha ficam no Android.

Cada ativação aceita um navegador. O código vale por até três minutos e expira após o primeiro pareamento bem-sucedido. A sessão termina após cinco minutos sem operações, vinte minutos desde a ativação, perda da rede selecionada, saída da tela, bloqueio do cofre ou encerramento manual. A verificação de conexão do painel não renova o prazo de inatividade.

## Pesquisa e decisão

| Alternativa | Aplicação no Arca |
| --- | --- |
| Site em nuvem ou túnel público | O acesso solicitado cabe em uma rede local. Hospedagem e contas acrescentariam infraestrutura e exposição sem facilitar o modo offline. |
| HTTP na rede local com PIN | O conteúdo e a sessão de um cofre exigem confidencialidade e autenticação do servidor. HTTPS desde a primeira resposta evita entregar o código ou o painel em claro. |
| Envio manual do arquivo criptografado | Continua útil como backup e transporte entre locais. O painel acessa e grava a mesma cópia que o Android, com confirmação de revisão para evitar sobrescritas concorrentes. |
| HTTPS temporário no celular | A autoridade local requer uma instalação explícita na primeira conexão. Depois disso, o pareamento é feito com código descartável e o servidor acompanha a tela e a rede escolhida. |

A implementação mantém chaves privadas no Android Keystore. O certificado do servidor é emitido para o IPv4 escolhido. A autoridade é dedicada à instalação e contém restrições de nomes para IPv4 privados. Um repositório de confiança pode aplicar às raízes regras diferentes das usadas no caminho de certificação. A instalação de uma raiz continua sendo uma decisão de confiança no dispositivo cliente. [Android Keystore](https://developer.android.com/privacy-and-security/keystore) · [RFC 5280](https://www.rfc-editor.org/rfc/rfc5280.html#section-6)

O servidor escuta só no IPv4 selecionado e valida a sub-rede e o endereço de destino. Requisições que alteram dados exigem origem HTTPS idêntica. O código tem 16 caracteres aleatórios em Base32 e o cookie de sessão usa `Secure`, `HttpOnly` e `SameSite=Strict`. Os recursos web são locais, as respostas desativam cache e o painel descarta os dados exibidos quando perde a sessão. [OWASP sobre sessões](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) · [OWASP sobre CSRF](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)

O APK usa as permissões normais `INTERNET` para a escuta local e `ACCESS_NETWORK_STATE` para selecionar e acompanhar a rede. Ele mantém o SDK de destino 35. Em uma migração futura para API 37, o acesso TCP local passa a depender da autorização `ACCESS_LOCAL_NETWORK`. Redes de convidados que isolam clientes podem impedir a conexão. [Android Developers sobre rede local](https://developer.android.com/privacy-and-security/local-network-permission)

## Imagem do painel

Ilustração gerada com o ImageGen integrado, convertida para WebP e incluída em [app/src/main/assets/lan/arca-vault.webp](../app/src/main/assets/lan/arca-vault.webp). As capturas do README são do painel em execução.

> Use case: product-mockup. Asset: wide banner photograph for the locally hosted Arca Offline family-vault dashboard, an original image. A single premium compact home document safe, charcoal powder-coated steel, tightly closed, with a well-crafted circular combination dial and one restrained ember-red accent light, set on a dark navy studio plinth. Frame the safe on the right third, medium-close three-quarter front view, with generous dark unobstructed negative space on the left for live application text, still legible when cropped wide. Subtle red edge light on the right and cool blue highlights reveal tactile materials, sober and reassuring editorial product photography rather than sci-fi. Full-bleed 3:2 landscape composition suitable for a wide crop, colors midnight navy, graphite and warm crimson. No lettering, numerals, logos, people, watermark, external interface, screens, loose papers or visible contents.
