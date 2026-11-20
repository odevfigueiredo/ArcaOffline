# Validação do Arca Offline 0.3.0

Testes realizados em 20 de novembro de 2026. APK de depuração para Android 8.0 ou superior. Compilação com JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, SDK e Build Tools 35.

## Resultado

| Área | O que foi verificado |
| --- | --- |
| Compilação | APK e APK de instrumentação gerados. Assinatura v2 e alinhamento ZIP válidos. Android Lint com 0 erros e 17 avisos sobre SDK de destino, ícones e textos em português no código. |
| Manifesto | Apenas `INTERNET` e `ACCESS_NETWORK_STATE`. Backup automático e tráfego HTTP em claro desativados. Renderizador PDF isolado e privado. |
| Criptografia e arquivos | Suítes JVM de criptografia e armazenamento aprovadas, incluindo o backup da versão 0.1, adulteração, limite de tamanho, gravação atômica e preservação do cofre após cancelamento. |
| Certificados | Testes Java e OpenSSL com chave EC, CA dedicada, SAN para o IPv4 exato e SHA-256. Testes Android com chaves não exportáveis e reuso da CA em novo processo. Certificado exportado pela interface do Android com SHA-256 igual ao exibido no celular. |
| Rede local em Android 8.0 | TLS 1.2, rotas permitidas, política CSP, respostas sem cache, acesso sem sessão recusado, Host e origem incompatíveis recusados, código incorreto e código reutilizado recusados, pareamento com cookie protegido, criação e exclusão, revisão obsoleta recusada, backup e fim do servidor no logout. |
| Navegador com Android 16 | Edge no Windows ligado por uma ponte TCP local ao APK, preservando TLS até o Android. TLS 1.3 validado separadamente com a autoridade exportada. IP incorreto e autoridade ausente foram recusados. O perfil temporário do navegador usou a impressão da chave já verificada. O repositório de certificados do Windows ficou intacto. |
| Gerenciamento no Edge | Pareamento, busca sem acento, prévia de texto, download de anexo, criação, edição, exclusão, revisão obsoleta, recarga de sessão, layout com 390 px sem rolagem lateral e encerramento pelo Android aprovados. A página esvaziou o painel ao detectar a desconexão. Zero requisições do painel a recursos externos. |
| Backup pelo navegador | O SHA-256 do download coincidiu com o arquivo privado no Android. O arquivo foi reaberto com a senha de teste e continha os sete registros sintéticos esperados. |
| Android sem Wi-Fi | A suíte de oito grupos voltou a passar em Android 16 com modo avião e Wi-Fi desligado. No Android 8.0, a instrumentação confirmou janela protegida e PDF paginado em processo isolado. |
| Validação visual | Seis capturas reais do APK no Edge, em desktop e tela estreita, revisadas e incluídas na [galeria do README](../README.md#-galeria). A imagem do cofre usada na interface foi criada com ImageGen. |

O teste web usou emuladores e uma ponte local para o navegador do Windows. Testes com aparelhos físicos na mesma rede, instalação da autoridade em clientes iOS e auditoria independente de segurança continuam pendentes.

## APK entregue

- `app/build/outputs/apk/debug/app-debug.apk`
- Pacote `br.com.arca.offline`, versão `0.3.0`, código `3`
- SHA-256 `0bff3dc9b789cbc054736a69a21bdee819ce03b09933f0b07f7feec5c1c72363`

A compilação ocorreu em uma cópia local fora do OneDrive para evitar o bloqueio de arquivos intermediários no Windows. O APK entregue é o mesmo binário usado nas verificações acima. A versão 0.2 introduziu a prévia segura de PDF, a restauração com confirmação e a compatibilidade já verificada com o primeiro backup de demonstração.
