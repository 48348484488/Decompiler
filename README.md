# SNES Deco

App Android para carregar ROMs de Super Nintendo (.sfc/.smc) e explorar como
o jogo funciona por dentro: header, desmontagem 65816, hex dump, paletas
BGR555 e tiles gráficos. Fase 2 vai adicionar um modo "Jogar" integrando um
core de emulação open-source (snes9x via libretro) por JNI.

## Status

- [x] Parser de header (LoROM/HiROM/ExHiROM, heurística de detecção)
- [x] Desmontador 65816 completo (256 opcodes), com rastreio heurístico de M/X via REP/SEP
- [x] Hex viewer
- [x] Decodificador de paleta BGR555
- [x] Decodificador de tiles planares (2/4/8bpp)
- [ ] Emulação jogável (core snes9x via JNI) — em andamento

## Build

O SDK/NDK do Android roda no GitHub Actions (`.github/workflows/build.yml`),
não localmente. Todo push na `main` (ou disparo manual em Actions) gera o
APK debug como artifact.

## Estrutura

```
app/src/main/java/com/diogo/snesdeco/
  rom/     -> parsing de header, mapeamento de endereço LoROM/HiROM
  disasm/  -> tabela de opcodes + desmontador 65816
  gfx/     -> decodificadores de paleta e tile
  ui/      -> Activities/Fragments (MainActivity, RomExplorerActivity + abas)
```
