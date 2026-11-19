# Relatório de validação do Arca Offline 0.2.0

Verificação em 19 de novembro de 2026. Compilação com JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, SDK 35 e Build Tools 35.0.0. Os emuladores Android 8.0 (API 26) e Android 16 (API 36) foram mantidos em modo avião, com Wi-Fi desligado e sem rede padrão ativa.

## Resultados

| Verificação | Resultado |
| --- | --- |
| `assembleDebug`, `assembleDebugAndroidTest` e `lintDebug` | APKs gerados. Lint com 0 erros e 17 avisos sobre SDK de destino, formato dos ícones e textos em português definidos no código. |
| Assinatura e alinhamento do APK | Assinatura de depuração v2 e alinhamento ZIP verificados. |
| Manifesto do APK | Sem permissões declaradas. Backup automático e tráfego HTTP em claro desativados. Renderizador PDF isolado e inacessível externamente. Uma instância da tela do cofre. |
| Criptografia | Seis grupos aprovados em Java 17 e em provedores criptográficos Android 8.0 e 16. Cobrem senha Unicode com espaços, nonce por gravação, adulteração, truncamento, limite de 32 MiB e compatibilidade com o backup original. |
| Política de senhas e compatibilidade | Cofres novos e senha trocada usam 600.000 iterações. Arquivo original com 310.000 iterações abre e continua legível após regravação. |
| Gravação | Suíte JVM aprovada. Cobertura para limites de leitura e cópia, substituição atômica, limpeza de arquivos temporários e preservação do cofre se a gravação for cancelada. |
| Estrutura do arquivo | Verificação no Android 8.0 com o backup original, UTF-8, anexos próximos do limite total e rejeição de JSON, data, Base64 e estrutura inválidos. |
| Instrumentação Android 16 | Oito grupos aprovados. Criação, abertura, senha errada, criação/edição/exclusão de registro, busca sem acentos, anexo, prévia de PDF, exportação, troca de senha, restauração e rebloqueio ao recriar a tela. O provedor MediaStore entregou arquivos sintéticos ao aplicativo nas operações instrumentadas. |
| Seletor de arquivos nativo no Android 16 | Backup original 0.1 restaurado pela pasta Downloads, com os dois registros fictícios e arquivo idêntico ao original. Ao voltar da tela inicial do Android, o app pediu a senha novamente. Uma seleção concluída depois de 60 segundos foi ignorada, com o cofre bloqueado e o arquivo intacto. |
| Android 8.0 | Instalação e abertura, criação e gravação, senha errada e correta após reiniciar o processo. Instrumentação de PDF aprovou duas páginas e limpeza do buffer ao fechar. A seleção de backup após o prazo de 60 segundos também foi ignorada sem alterar o cofre. |

A exportação criptografada foi comparada byte a byte com o cofre privado. Backups adulterados ou com esquema incompatível foram recusados e mantiveram o arquivo anterior. Um backup válido só substituiu o cofre existente depois da confirmação.

## Problemas corrigidos

- O projeto original chegava sem um Gradle Wrapper completo. A versão atual inclui o Wrapper e validação por SHA-256 da distribuição, além de checagens no workflow do GitHub.
- A descriptografia, o acesso a arquivos e a criptografia bloqueavam a interface. Agora ocorrem fora da interface, com descarte de resultados de sessões bloqueadas.
- A restauração foi dividida em validação e confirmação, e as gravações usam um arquivo temporário criptografado com substituição atômica. O aplicativo recusa conteúdo fora dos limites ou do esquema aceito.
- O bloqueio foi reforçado para fechar diálogos, invalidar operações pendentes da sessão e ignorar resultados vencidos do seletor.
- PDFs compatíveis abrem dentro do cofre e em processo isolado, sem arquivo temporário em claro. A leitura de imagens tem limite de resolução e o texto tem limite de prévia.
- A documentação antiga omitia o limite de 350 registros que já existia no validador. O manual agora distingue esse limite do tamanho por anexo e do tamanho total em JSON.

## APK verificado

- Arquivo `app/build/outputs/apk/debug/app-debug.apk`
- Versão `0.2.0`, código `2`, pacote `br.com.arca.offline`
- SHA-256 `bffa602de77d9d906adaabbe8525ae0727e9141bc7adef3d8e6f5416268075bc`

O projeto foi compilado em uma cópia local da pasta porque o OneDrive bloqueou arquivos intermediários criados pelo Gradle no Windows. Uma compilação limpa com as dependências em cache também foi aprovada. O APK entregue corresponde aos bytes instalados na validação dos emuladores e usa assinatura de depuração e destina-se à instalação de teste. A validação foi feita em emuladores. Testes em aparelho físico, múltiplos fabricantes e uma auditoria independente de segurança permanecem pendentes.
