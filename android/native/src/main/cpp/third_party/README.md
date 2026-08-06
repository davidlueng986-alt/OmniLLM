# third_party — llama.cpp vendor pin

## Pin (must match `engines/llama-cpp/UPSTREAM.lock`)

| Field | Value |
|---|---|
| Repository | `https://github.com/ggml-org/llama.cpp` |
| Tag | `b9999` |
| Commit | `47c786924ad1ab7e91da2cdc72fcdb563780c2bd` |
| License | MIT (`LICENSE`) |

## Fetch / refresh (host or CI)

```bash
# From repo root
VENDOR="android/native/src/main/cpp/third_party/llama.cpp"
TAG="b9999"
COMMIT="47c786924ad1ab7e91da2cdc72fcdb563780c2bd"

rm -rf "$VENDOR"
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$VENDOR"
cd "$VENDOR"
test "$(git rev-parse HEAD)" = "$COMMIT"

# Optional: prune non-library trees (Windows long-path friendly)
# Keep: cmake ggml include src common vendor licenses CMakeLists.txt LICENSE
```

### PowerShell

```powershell
$Vendor = "android\native\src\main\cpp\third_party\llama.cpp"
$Tag = "b9999"
if (Test-Path $Vendor) { Remove-Item -Recurse -Force $Vendor }
git clone --depth 1 --branch $Tag https://github.com/ggml-org/llama.cpp.git $Vendor
```

### CMake FetchContent alternative

If the tree is absent, CMake builds **EXPERIMENTAL_FIXTURE only**
(`OMNILLM_HAS_LLAMA_CPP` off). To force FetchContent in a CI cache job:

```cmake
# Optional sketch — not enabled by default (prefer vendored pin above)
include(FetchContent)
FetchContent_Declare(
  llama_cpp
  GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
  GIT_TAG        b9999
  GIT_SHALLOW    TRUE
)
# Then point OMNILLM_LLAMA_VENDOR_DIR at the populated source and
# set OMNILLM_LLAMA_WITH_UPSTREAM=ON (see CMakeLists.txt).
```

### CI optional cache keys

- `llama-cpp-${TAG}-${COMMIT}-${ABI}-${NDK}`
- Cache directory: `android/native/src/main/cpp/third_party/llama.cpp`
- Invalidate when `UPSTREAM.lock` tag/commit changes

## Build behavior

| Condition | Result |
|---|---|
| `third_party/llama.cpp/CMakeLists.txt` present + `OMNILLM_LLAMA_WITH_UPSTREAM=ON` | Link real llama.cpp; path/FD GGUF load available |
| Tree missing | EXPERIMENTAL_FIXTURE C++ loop only (still real native `.so`) |

Gradle passes 16 KB flexible page sizes; CMake also sets
`-Wl,-z,max-page-size=16384` / `common-page-size=16384`.

## Honesty

- Presence of upstream sources or `.so` **does not** mark cells QUALIFIED / SUPPORTED.
- EXPERIMENTAL_FIXTURE is packaging / exploratory only.
