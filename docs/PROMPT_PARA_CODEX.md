# Tarefa pronta para copiar no Codex

Abra o projeto Android `Arca_Offline_v0.1.0`, cujo app se chama **Arca Offline**.

Objetivo: compilar um APK de testes e verificar integralmente a versão 0.1 sem adicionar qualquer dependência de internet em runtime.

1. Configure Java 17, Gradle 8.9, Android Gradle Plugin 8.7.3 e compileSdk 35.
2. Execute `tests/CryptoBoxTest.java`; não altere o formato criptográfico salvo em `.arca` sem versionamento explícito.
3. Compile `:app:assembleDebug`; resolva eventuais erros de compilação.
4. Faça testes instrumentados em emulador Android 8.0+ para criar/desbloquear cofre, registros, anexos, backup/restauração, troca de senha, arquivos adulterados, negação de senha e modo avião.
5. Mantenha todo o armazenamento privado **criptografado em repouso**; nunca gere arquivos temporários descriptografados sem confirmação e aviso ao usuário.
6. Revise código em busca de vazamento de dados sensíveis, logs, backup automático de sistema, captura de tela, manipulação de senhas, sessões destravadas e estados incompletos.
7. Não adicione INTERNET, analytics, anúncios ou telemetria.
8. Forneça o `app-debug.apk`, instruções de instalação e um relatório de testes com os resultados de compilação reais.

Melhorias futuras opcionais: visualizador de PDFs sem arquivos temporários em disco, autenticação biométrica vinculada a Android Keystore, lembrete de validade de documentos e organização em pastas; preserve o modo offline.
