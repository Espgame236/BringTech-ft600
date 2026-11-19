# BringTech FT600

App Android (OBD2 via Bluetooth) com painel estilo FT600:

- procura aparelhos sozinho (Bluetooth clássico + BLE) e pede permissão ao abrir
- conta-giros (RPM), velocidade, temp. água, carga, TPS, temp. ar, tensão, estado da luz MIL
- **LER FALHAS** (modo 03)
- **APAGAR FALHAS** (modo 04): apaga os códigos e a luz do painel, e confere depois se a ECU confirmou

Compila pelo GitHub Actions (`.github/workflows/build-apk.yml`). O APK sai em
Actions > último build > Artifacts > BringTech-FT600-APK.
