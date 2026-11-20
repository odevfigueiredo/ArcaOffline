# Segurança e backup

## Arquivo do cofre

Registros e anexos ficam no arquivo privado `private.arca`. O aplicativo usa AES-256-GCM com nonce aleatório de 96 bits a cada gravação. O cabeçalho autenticado `ARCAOF01` contém as iterações da derivação, um sal aleatório de 16 bytes e o nonce. O conteúdo é JSON UTF-8 com anexos em Base64. A forma criptografada acrescenta 56 bytes ao conteúdo de até 32 MiB.

A senha deriva a chave com PBKDF2-HMAC-SHA256. Cofres novos e trocas de senha usam 600.000 iterações. Abrir ou restaurar um arquivo existente usa o custo registrado no cabeçalho, inclusive as 310.000 iterações do Arca 0.1. Até trocar a senha dentro do aplicativo, novas gravações desse cofre continuam usando a chave e o custo existentes.

A senha é pedida no aplicativo e a chave é mantida durante a sessão. O aplicativo descarta as referências aos registros e limpa os buffers que controla ao bloquear. O Android e as bibliotecas criptográficas podem manter cópias internas da memória enquanto a processam.

## Exportar e restaurar

1. Em **Backup**, exporte o arquivo `.arca` para um destino escolhido no Android. Guarde cópias em suportes separados.
2. Teste a restauração em um aparelho de testes com a senha correspondente ao arquivo. Restaurar substitui todos os registros e passa a usar a senha do backup. Guarde um backup do cofre atual antes de restaurar outro.
3. Após trocar a senha, exporte e teste uma cópia nova. Backups antigos conservam a senha original.
4. Para ler um PDF compatível, use **Pré-visualizar anexo**. A prévia tem paginação e funciona usando memória, em um processo separado. Alguns PDFs protegidos por senha, danificados ou incompatíveis podem exigir outro leitor.
5. **Exportar anexo descriptografado** grava uma cópia legível sem senha. Prefira um destino local sob seu controle. A exclusão de um arquivo em memória flash não garante sua eliminação física.

## Limites de proteção

Use uma frase-senha exclusiva. O cofre usa uma senha principal local. A recuperação depende da senha correspondente ao arquivo de backup. A integridade do Android, as atualizações de segurança e o teclado do aparelho também afetam a privacidade com o cofre aberto. A aplicação passou por testes de implementação. A auditoria independente permanece pendente.

## Sessões pelo navegador

O cofre só atende ao navegador durante uma sessão HTTPS iniciada pelo usuário no Android. A autoridade da instalação é exportada para conferência e instalação explícita no cliente. Use um navegador de confiança, especialmente para abrir ou salvar anexos. O término da sessão fecha o servidor e limpa o painel. Arquivos já baixados continuam sob responsabilidade do usuário. [Guia de acesso web](ACESSO_WEB.md)