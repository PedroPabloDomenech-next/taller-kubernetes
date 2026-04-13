# taller-kubernetes

Scripts para preparar un entorno Linux de taller con Docker, kubectl, minikube, Argo CD CLI y herramientas relacionadas.

## Uso

```bash
chmod +x scripts/install-k8s-tools.sh scripts/verify-k8s-tools.sh scripts/uninstall-k8s-tools.sh
./scripts/install-k8s-tools.sh
./scripts/verify-k8s-tools.sh
./scripts/uninstall-k8s-tools.sh
```

## Alcance

- `install-k8s-tools.sh`: instala dependencias base, Docker Engine, kubectl, minikube y Argo CD CLI.
- `verify-k8s-tools.sh`: verifica que los binarios existen y muestra una versión básica de cada uno.
- `uninstall-k8s-tools.sh`: elimina Docker Engine, kubectl, minikube, Argo CD CLI y los artefactos de repositorio anadidos por el instalador, sin borrar volumenes ni configuraciones persistentes.

El instalador está orientado a distribuciones basadas en Debian/Ubuntu con `apt`.
