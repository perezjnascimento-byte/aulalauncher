import os
import shutil
from pathlib import Path

ROOT = Path('.').resolve()

def find_first(name: str):
    for p in ROOT.rglob(name):
        if '.git' not in p.parts:
            return p
    return None

manifest_standard = next((p for p in ROOT.rglob('AndroidManifest.xml') if p.parts[-3:] == ('src', 'main', 'AndroidManifest.xml') and '.git' not in p.parts), None)
proj_dir = manifest_standard.parents[3] if manifest_standard else None

flat_main_kt = find_first('MainActivity.kt')
flat_manifest = ROOT / 'AndroidManifest.xml'
has_root_flat = (ROOT / 'MainActivity.kt').exists() or flat_manifest.exists()

if proj_dir and not has_root_flat:
    (ROOT / '.aura_project_dir').write_text(str(proj_dir))
    print(f'Projeto padrao encontrado em: {proj_dir}')
    raise SystemExit(0)

flat_dir = proj_dir if proj_dir else (flat_main_kt.parent if flat_main_kt else ROOT)
print(f'Organizando e sincronizando arquivos em: {flat_dir}')

dirs = [
    flat_dir / 'app/src/main/java/com/aura/launcher',
    flat_dir / 'app/src/main/res/values',
    flat_dir / 'app/src/main/res/mipmap-anydpi-v26',
    flat_dir / 'app/src/main/res/drawable',
    flat_dir / 'gradle/wrapper',
]
for d in dirs:
    d.mkdir(parents=True, exist_ok=True)

moves = {
    'AndroidManifest.xml': 'app/src/main/AndroidManifest.xml',
    'MainActivity.kt': 'app/src/main/java/com/aura/launcher/MainActivity.kt',
    'AuraNotificationListener.kt': 'app/src/main/java/com/aura/launcher/AuraNotificationListener.kt',
    'styles.xml': 'app/src/main/res/values/styles.xml',
    'ic_launcher.xml': 'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml',
    'ic_launcher_round.xml': 'app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml',
    'ic_launcher_background.xml': 'app/src/main/res/drawable/ic_launcher_background.xml',
    'ic_launcher_foreground.xml': 'app/src/main/res/drawable/ic_launcher_foreground.xml',
    'gradle-wrapper.properties': 'gradle/wrapper/gradle-wrapper.properties',
}

for src_name, dst_rel in moves.items():
    src = ROOT / src_name
    if not src.exists():
        src = flat_dir / src_name
    dst = flat_dir / dst_rel
    if src.exists() and src.resolve() != dst.resolve():
        shutil.move(str(src), str(dst))
        print(f'Sincronizado: {src_name} -> {dst_rel}')

root_gradle = flat_dir / 'build.gradle.kts'
app_gradle = flat_dir / 'app/build.gradle.kts'
if root_gradle.exists() and 'com.aura.launcher' in root_gradle.read_text(encoding='utf-8', errors='ignore'):
    shutil.move(str(root_gradle), str(app_gradle))

if not root_gradle.exists():
    root_gradle.write_text(
        'plugins {\n'
        '    id("com.android.application") version "8.5.2" apply false\n'
        '    id("org.jetbrains.kotlin.android") version "1.9.24" apply false\n'
        '}\n',
        encoding='utf-8',
    )

if not app_gradle.exists():
    app_gradle.write_text(
        'plugins {\n'
        '    id("com.android.application")\n'
        '    id("org.jetbrains.kotlin.android")\n'
        '}\n'
        'android {\n'
        '    namespace = "com.aura.launcher"\n'
        '    compileSdk = 35\n'
        '    defaultConfig {\n'
        '        applicationId = "com.aura.launcher"\n'
        '        minSdk = 26\n'
        '        targetSdk = 35\n'
        '        versionCode = 4\n'
        '        versionName = "1.3"\n'
        '    }\n'
        '    buildTypes { release { isMinifyEnabled = false } }\n'
        '    compileOptions {\n'
        '        sourceCompatibility = JavaVersion.VERSION_17\n'
        '        targetCompatibility = JavaVersion.VERSION_17\n'
        '    }\n'
        '    kotlinOptions { jvmTarget = "17" }\n'
        '}\n'
        'dependencies {\n'
        '    implementation("androidx.core:core-ktx:1.13.1")\n'
        '    implementation("androidx.appcompat:appcompat:1.7.0")\n'
        '    implementation("androidx.activity:activity-ktx:1.9.3")\n'
        '    implementation("androidx.webkit:webkit:1.11.0")\n'
        '}\n',
        encoding='utf-8',
    )

if not (flat_dir / 'settings.gradle.kts').exists():
    (flat_dir / 'settings.gradle.kts').write_text(
        'pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\n'
        'dependencyResolutionManagement {\n'
        '    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n'
        '    repositories { google(); mavenCentral() }\n'
        '}\n'
        'rootProject.name = "AuraLauncher"\n'
        'include(":app")\n',
        encoding='utf-8',
    )

if not (flat_dir / 'gradle.properties').exists():
    (flat_dir / 'gradle.properties').write_text(
        'org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\n'
        'android.useAndroidX=true\n'
        'kotlin.code.style=official\n',
        encoding='utf-8',
    )

styles_dst = flat_dir / 'app/src/main/res/values/styles.xml'
if not styles_dst.exists():
    styles_dst.write_text(
        '<resources>\n'
        '    <style name="Theme.AuraLauncher" parent="Theme.AppCompat.NoActionBar">\n'
        '        <item name="android:windowBackground">#15120F</item>\n'
        '        <item name="android:statusBarColor">@android:color/transparent</item>\n'
        '        <item name="android:navigationBarColor">@android:color/transparent</item>\n'
        '        <item name="android:windowLightStatusBar">false</item>\n'
        '    </style>\n'
        '</resources>\n',
        encoding='utf-8',
    )

manifest_dst = flat_dir / 'app/src/main/AndroidManifest.xml'
if manifest_dst.exists() and 'ic_launcher' in manifest_dst.read_text(encoding='utf-8', errors='ignore'):
    bg = flat_dir / 'app/src/main/res/drawable/ic_launcher_background.xml'
    fg = flat_dir / 'app/src/main/res/drawable/ic_launcher_foreground.xml'
    ic = flat_dir / 'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml'
    ic_r = flat_dir / 'app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml'
    if not bg.exists():
        bg.write_text('<?xml version="1.0" encoding="utf-8"?><shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="#15120F" /></shape>\n', encoding='utf-8')
    if not fg.exists():
        fg.write_text('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108"><path android:fillColor="#00000000" android:pathData="M54,30 A24,24 0 1,1 53.99,30 Z" android:strokeWidth="2.8" android:strokeColor="#E9A06B" android:strokeAlpha="0.85" /></vector>\n', encoding='utf-8')
    if not ic.exists():
        ic_xml = '<?xml version="1.0" encoding="utf-8"?><adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/ic_launcher_background" /><foreground android:drawable="@drawable/ic_launcher_foreground" /></adaptive-icon>\n'
        ic.write_text(ic_xml, encoding='utf-8')
        ic_r.write_text(ic_xml, encoding='utf-8')

(ROOT / '.aura_project_dir').write_text(str(flat_dir))
print(f'Projeto pronto em: {flat_dir}')
