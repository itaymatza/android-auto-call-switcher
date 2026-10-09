#!/usr/bin/env bash
# One-time owner setup for persistent GitHub Actions debug APK signing.
set -euo pipefail

REPOSITORY="itaymatza/android-auto-call-switcher"
ENVIRONMENT="debug-signing"
SIGNING_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/car-call-router"
KEYSTORE="$SIGNING_DIR/debug-signing.p12"
PASSWORD_FILE="$SIGNING_DIR/debug-signing-password"
KEY_ALIAS="car-call-router-debug"

for tool in gh keytool openssl base64; do
    command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
gh auth status >/dev/null
umask 077
mkdir -p "$SIGNING_DIR"
if [[ -e "$KEYSTORE" && -e "$PASSWORD_FILE" ]]; then
    IFS= read -r key_password < "$PASSWORD_FILE"
elif [[ -e "$KEYSTORE" || -e "$PASSWORD_FILE" ]]; then
    echo "Incomplete signing backup in $SIGNING_DIR; restore both files before retrying." >&2
    exit 1
else
    key_password="$(openssl rand -hex 32)"
    printf '%s\n' "$key_password" > "$PASSWORD_FILE"
    if ! keytool -genkeypair -noprompt -storetype PKCS12 \
        -keystore "$KEYSTORE" -storepass "$key_password" -keypass "$key_password" \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 3072 -validity 36500 \
        -dname "CN=Car Call Router Debug, O=Car Call Router"; then
        rm -f "$KEYSTORE" "$PASSWORD_FILE"
        exit 1
    fi
fi
chmod 600 "$KEYSTORE" "$PASSWORD_FILE"

certificate_sha256="$(LC_ALL=C keytool -list -v -keystore "$KEYSTORE" \
    -storepass "$key_password" -alias "$KEY_ALIAS" \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n 1 \
    | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$certificate_sha256" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Could not read the expected signing certificate from the keystore." >&2
    exit 1
fi

gh api --method PUT "repos/$REPOSITORY/environments/$ENVIRONMENT" >/dev/null
base64 < "$KEYSTORE" | tr -d '\n' \
    | gh secret set DEBUG_KEYSTORE_BASE64 --env "$ENVIRONMENT" --repo "$REPOSITORY"
printf '%s' "$key_password" \
    | gh secret set DEBUG_KEYSTORE_PASSWORD --env "$ENVIRONMENT" --repo "$REPOSITORY"
printf '%s' "$KEY_ALIAS" \
    | gh secret set DEBUG_KEY_ALIAS --env "$ENVIRONMENT" --repo "$REPOSITORY"
printf '%s' "$key_password" \
    | gh secret set DEBUG_KEY_PASSWORD --env "$ENVIRONMENT" --repo "$REPOSITORY"
gh variable set DEBUG_SIGNING_CERT_SHA256 --body "$certificate_sha256" \
    --env "$ENVIRONMENT" --repo "$REPOSITORY"

echo "Protected debug signing is configured. Back up $SIGNING_DIR securely."
echo "Never delete or rotate this key while installed APKs need in-place updates."
