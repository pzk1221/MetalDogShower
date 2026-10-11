# MetalDogShower history backup

The receiver listens only on `127.0.0.1:8091` and is exposed through the existing Caddy HTTPS
site at:

- `POST /api/metaldog/v1/register`
- `POST /api/metaldog/v1/backup`

Each app installation generates an RSA signing key in Android Keystore. The server stores the
public key and accepts a backup only when the request signature verifies. The service has no read
endpoint; history can be inspected by the owner over SSH in `/var/lib/metaldog-backup`.
