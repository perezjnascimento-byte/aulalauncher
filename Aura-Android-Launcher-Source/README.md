# Aura Android Launcher (Galaxy S26 / Android 15+)

> **Importante:** celulares Android não instalam arquivos `.zip` diretamente. Este pacote é o **projeto fonte** que gera o arquivo `.apk` oficial com `CATEGORY_HOME` para o Samsung Galaxy S26 reconhecer a Aura como **App de início (Launcher padrão)**.

## Como gerar o .apk e instalar oficialmente no Galaxy S26

### Opção 1 · GitHub Actions (sem PC / sem Android Studio)
1. Descompacte este `.zip` no computador ou celular.
2. Envie **todo o conteúdo** (incluindo `settings.gradle.kts`, `build.gradle.kts`, `app/` e `.github/`) para o seu repositório no **GitHub**.
   - *Dica para Windows/Navegador:* ao usar **Upload files** no GitHub, arraste os arquivos de dentro da pasta descompactada (não apenas o arquivo `.yml` sozinho). O workflow detecta automaticamente mesmo se ficar dentro de uma subpasta.
3. No GitHub, abra a aba **Actions** → **Build Aura Launcher APK** → **Run workflow**.
4. Quando concluir (~2 min), abra o workflow concluído e baixe **AuraLauncher-S26-APK** (`Aura-Launcher-S26.apk`) no seu Galaxy S26.
5. Ao abrir o `.apk` no S26, permita **Instalar apps desconhecidos** para o Chrome/Meus Arquivos e, se o Play Protect perguntar, toque em **Mais detalhes → Instalar assim mesmo**.

### Opção 2 · Android Studio no computador
1. Descompacte este `.zip` e abra a pasta no **Android Studio**.
2. Conecte o Galaxy S26 via USB (Depuração USB ativada) e clique em **Run ▶**, ou gere o APK em **Build > Build APK(s)**.

## Como definir a Aura como Launcher Oficial no Samsung Galaxy S26
1. Abra **Configurações** no S26.
2. Entre em **Aplicativos** → **Escolher aplicativos padrão**.
3. Toque em **App de início** (ou *Tela inicial*).
4. Selecione **Aura**.

URL oficial vinculada à ponte nativa: https://ais-pre-jd7xmhrwywshavy6m2tnrs-576801925631.us-west2.run.app
