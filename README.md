<img width="100%" src="https://capsule-render.vercel.app/api?type=waving&color=0:0F172A,100:0D9488&height=190&section=header&text=Arca%20Offline&fontSize=48&fontColor=ffffff&animation=fadeIn&fontAlignY=35&desc=Seu%20cofre%20digital%2C%20sem%20depender%20da%20internet&descAlignY=58&descSize=17" alt="Arca Offline" />

<div align="center">
  <p><strong>Um cofre digital para organizar e proteger informações essenciais da família, com acesso local e backups criptografados.</strong></p>
  <p>
    <img src="https://img.shields.io/badge/Status-Em_desenvolvimento-0D9488?style=for-the-badge" alt="Status: em desenvolvimento" />
    <img src="https://img.shields.io/badge/Vers%C3%A3o-0.2.0-0F766E?style=for-the-badge" alt="Versão 0.2.0" />
    <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 8.0 ou superior" />
    <img src="https://img.shields.io/badge/Funciona-Offline-181717?style=for-the-badge" alt="Funciona offline" />
  </p>
</div>

---

## 🔐 Sobre

**Arca Offline** é um aplicativo Android voltado ao armazenamento privado de documentos, contatos e informações importantes. O objetivo é manter dados essenciais acessíveis mesmo sem conexão com a internet, com criptografia local, bloqueio por senha e opções de backup e restauração.

O aplicativo é independente de serviços em nuvem: os dados permanecem no dispositivo, e a exportação de backups fica sob o controle do usuário.

---

## ✨ Funcionalidades

- Registros para documentos, contatos, saúde, seguros, finanças e planos de emergência.
- Campos de título, categoria, notas, telefone e data importante.
- Busca por registros sem distinção entre maiúsculas, minúsculas e acentos.
- Anexos de até **8 MiB** por registro, com visualização interna de imagens, textos e PDFs compatíveis.
- Limite de **350 registros** e **32 MiB** de conteúdo serializado.
- Criação de cofre com senha principal de pelo menos **10 caracteres**.
- Exportação e restauração de backups criptografados no formato `.arca`.
- Bloqueio do aplicativo ao passar para segundo plano.
- Proteção contra capturas de tela e exclusão do cofre dos backups automáticos do Android.
- Seleção de arquivos e destinos locais pelo seletor nativo do sistema.

---

## 🧱 Tecnologias

<div align="center">
  <img src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Java_17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 17" />
  <img src="https://img.shields.io/badge/Gradle_8.9-02303A?style=for-the-badge&logo=gradle&logoColor=white" alt="Gradle 8.9" />
  <img src="https://img.shields.io/badge/AES--256--GCM-0F766E?style=for-the-badge" alt="AES-256-GCM" />
  <img src="https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white" alt="GitHub Actions" />
</div>

---

## 🛡️ Privacidade e segurança

O cofre e os arquivos de backup utilizam **AES-256-GCM**. Para novos cofres, a derivação da chave usa **PBKDF2-HMAC-SHA256 com 600.000 iterações**.

A restauração verifica a senha, a integridade e o conteúdo do backup antes de substituir os dados existentes. O aplicativo também mantém compatibilidade com backups da versão 0.1.

> **Importante:** não existe recuperação da senha principal. Mantenha a senha em local seguro e preserve cópias verificadas dos documentos críticos em dispositivos separados. O projeto ainda não passou por auditoria de segurança independente.

Mais detalhes: [Segurança e backup](docs/SEGURANCA_E_BACKUP.md).

---

## 🗂️ Estrutura do projeto

```text
ArcaOffline/
├── .github/
│   └── workflows/
│       └── build-apk.yml
├── app/
│   └── src/
│       └── main/
│           └── java/br/com/arca/offline/
├── docs/
├── examples/
├── tests/
├── gradlew
└── README.md
```

---

## 🚀 Como começar

### 1. Requisitos

- Android **8.0 ou superior** para instalar o aplicativo.
- **JDK 17**, **Android SDK Platform 35** e **Build Tools 35.0.0** para compilar.
- Android Studio ou terminal com `JAVA_HOME` e `ANDROID_HOME` configurados.

### 2. Clone o repositório

```bash
git clone https://github.com/odevfigueiredo/ArcaOffline.git
cd ArcaOffline
```

### 3. Compile o APK

**Windows (PowerShell):**

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

**Linux/macOS:**

```bash
bash ./gradlew :app:assembleDebug :app:lintDebug
```

O arquivo gerado estará em `app/build/outputs/apk/debug/app-debug.apk`.

Também é possível gerar o APK pelo **GitHub Actions**: abra a aba **Actions**, escolha **Compilar APK de testes**, execute o workflow e baixe o artefato `arca-offline-debug-apk` quando a compilação terminar com sucesso.

### 4. Configure o cofre

1. Instale o APK no Android.
2. Defina uma senha principal com pelo menos 10 caracteres ou restaure um backup `.arca`.
3. Cadastre os registros e documentos que deseja armazenar.
4. Exporte um backup e valide a restauração antes de depender dele.

Para testar com dados fictícios, utilize [o backup de demonstração](examples/backup-exemplo.arca), com a senha `ExemploArca2026!`.

**Atenção:** antes de substituir uma instalação assinada com outra chave, exporte um backup. A desinstalação do aplicativo remove o cofre armazenado localmente.

---

## 🧪 Testes e qualidade

O projeto inclui testes para criptografia e armazenamento local, além de uma rotina automatizada de compilação e verificação do APK.

```bash
mkdir -p tests/build
javac --release 17 -encoding UTF-8 -d tests/build \
  app/src/main/java/br/com/arca/offline/CryptoBox.java \
  app/src/main/java/br/com/arca/offline/VaultStorage.java \
  tests/CryptoBoxTest.java tests/VaultStorageTest.java
java -cp tests/build CryptoBoxTest
java -cp tests/build br.com.arca.offline.VaultStorageTest
```

O workflow do GitHub Actions prepara o ambiente Android, executa esses testes, compila o APK e verifica sua assinatura e permissões declaradas. Os testes instrumentados precisam de um emulador Android apropriado e não são executados automaticamente apenas pela compilação do APK de teste.

Consulte o [relatório de testes](docs/RELATORIO_DE_TESTES.md).

---

## 🧭 Próximos passos

- Ampliar os testes em dispositivos e versões do Android.
- Aprimorar a experiência de navegação e a organização dos registros.
- Reforçar a documentação dos procedimentos de backup e recuperação.
- Submeter a implementação de segurança a uma revisão independente.

---

<div align="center">
  <p>Desenvolvido por <a href="https://github.com/odevfigueiredo">Jonatha Figueiredo</a></p>
  <img width="100%" src="https://capsule-render.vercel.app/api?type=waving&color=0:0D9488,100:0F172A&height=120&section=footer" alt="" />
</div>
