## Poke App en Kubernetes

Este directorio está dividido en varios manifiestos y se despliega con Kustomize.

Estructura:

- una carpeta por elemento (`auth`, `back`, `front`, `auth-db`, `ingress`, `namespace`, `secret`)
- un YAML por recurso de Kubernetes (`deployment.yaml`, `service.yaml`, `persistent-volume-claim.yaml`, etc.)

Este ejemplo publica una sola entrada HTTP con `Ingress`:

- `/` hacia `front`
- `/api/v2` hacia `back`
- `/auth` hacia `auth`

La base de datos `auth-db` solo queda accesible dentro del clúster.

### Idea de red

- `front` es una SPA y habla con el mismo host público.
- `back` y `auth` permanecen como `ClusterIP`.
- `Ingress` actúa como reverse proxy.

### Host esperado

El manifiesto usa `poke.local` como host de ejemplo.

Si trabajas con Minikube, añade una entrada en `/etc/hosts` apuntando la IP de Minikube:

```text
<MINIKUBE_IP> poke.local
```

### Imágenes

El manifiesto asume estas imágenes locales:

- `poke-front:latest`
- `poke-back:latest`
- `poke-auth:latest`

Ejemplo de build manual:

```bash
docker build -t poke-auth:latest poke-app/auth
docker build -t poke-back:latest poke-app/back
docker build -t poke-front:latest poke-app/front \
  --build-arg VITE_API_BASE_URL=/api/v2 \
  --build-arg VITE_AUTH_BASE_URL=/auth
```

### Aplicación

```bash
kubectl apply -k poke-app/k8s/
```

### Despliegue end-to-end en Minikube

Script equivalente:

```bash
chmod +x scripts/deploy-minikube-e2e.sh
./scripts/deploy-minikube-e2e.sh
```

1. Arranca Minikube:

```bash
minikube start
```

2. Activa el controlador Ingress:

```bash
minikube addons enable ingress
```

3. Construye las imágenes dentro del daemon Docker de Minikube:

```bash
eval $(minikube docker-env)
docker build -t poke-auth:latest poke-app/auth
docker build -t poke-back:latest poke-app/back
docker build -t poke-front:latest poke-app/front \
  --build-arg VITE_API_BASE_URL=/api/v2 \
  --build-arg VITE_AUTH_BASE_URL=/auth
```

4. Aplica los manifiestos:

```bash
kubectl apply -k poke-app/k8s/
```

5. Espera a que todo quede listo:

```bash
kubectl -n poke-app rollout status deployment/auth-db
kubectl -n poke-app rollout status deployment/auth
kubectl -n poke-app rollout status deployment/back
kubectl -n poke-app rollout status deployment/front
```

6. Obtén la IP de Minikube:

```bash
minikube ip
```

7. Añade el host localmente usando esa IP:

```text
<MINIKUBE_IP> poke.local
```

8. Abre la aplicación:

```bash
curl http://poke.local/
xdg-open http://poke.local/
```

### Comandos útiles de verificación

```bash
kubectl get pods -n poke-app
kubectl get svc -n poke-app
kubectl get ingress -n poke-app
kubectl describe ingress poke-app -n poke-app
kubectl logs -n poke-app deployment/auth
kubectl logs -n poke-app deployment/back
kubectl logs -n poke-app deployment/front
```

### Actualizar una app ya desplegada

Si cambias código de `front`, `back` o `auth`, en Minikube no hace falta publicar a un registry externo. El flujo normal es reconstruir la imagen dentro del daemon Docker de Minikube y reiniciar el `Deployment`.

Script equivalente:

```bash
chmod +x scripts/apply-pod-changes.sh
./scripts/apply-pod-changes.sh front
./scripts/apply-pod-changes.sh back
./scripts/apply-pod-changes.sh auth
./scripts/apply-pod-changes.sh manifests
```

1. Conecta tu shell al Docker de Minikube:

```bash
eval $(minikube docker-env)
```

2. Reconstruye solo la imagen que haya cambiado.

Frontend:

```bash
docker build -t poke-front:latest poke-app/front \
  --build-arg VITE_API_BASE_URL=/api/v2 \
  --build-arg VITE_AUTH_BASE_URL=/auth
```

Backend:

```bash
docker build -t poke-back:latest poke-app/back
```

Auth:

```bash
docker build -t poke-auth:latest poke-app/auth
```

3. Reinicia el `Deployment` correspondiente para que monte la nueva imagen:

Frontend:

```bash
kubectl -n poke-app rollout restart deployment/front
kubectl -n poke-app rollout status deployment/front
```

Backend:

```bash
kubectl -n poke-app rollout restart deployment/back
kubectl -n poke-app rollout status deployment/back
```

Auth:

```bash
kubectl -n poke-app rollout restart deployment/auth
kubectl -n poke-app rollout status deployment/auth
```

4. Comprueba que el cambio quedó aplicado:

```bash
kubectl get pods -n poke-app
kubectl logs -n poke-app deployment/front
kubectl logs -n poke-app deployment/back
kubectl logs -n poke-app deployment/auth
```

### Actualizar configuración o manifiestos

Si lo que cambias es el YAML de Kubernetes, reaplica el manifiesto:

```bash
kubectl apply -k poke-app/k8s/
```

Si además cambió la imagen, reconstruye primero la imagen y luego relanza el `Deployment`.

### Limpiar el despliegue y volver a aplicar desde cero

Si quieres dejar el entorno limpio para repetir el despliegue completo, borra primero los recursos de Kubernetes y, si quieres reiniciar también los datos, elimina el PVC de la base de datos.

Script equivalente:

```bash
chmod +x scripts/cleanup-deployment.sh
./scripts/cleanup-deployment.sh
./scripts/cleanup-deployment.sh --delete-pvc
```

1. Borra todos los recursos creados por Kustomize:

```bash
kubectl delete -k poke-app/k8s/
```

2. Asegúrate de que no queden recursos en el namespace:

```bash
kubectl get all -n poke-app
kubectl get ingress -n poke-app
kubectl get secret -n poke-app
```

3. Si quieres empezar también con la base de datos vacía, elimina el volumen persistente:

```bash
kubectl delete pvc auth-db-data -n poke-app
```

4. Si vas a reconstruir imágenes locales en Minikube, conecta tu shell a su Docker:

```bash
eval $(minikube docker-env)
```

5. Reconstruye las imágenes:

```bash
docker build -t poke-auth:latest poke-app/auth
docker build -t poke-back:latest poke-app/back
docker build -t poke-front:latest poke-app/front \
  --build-arg VITE_API_BASE_URL=/api/v2 \
  --build-arg VITE_AUTH_BASE_URL=/auth
```

6. Vuelve a aplicar el despliegue:

```bash
kubectl apply -k poke-app/k8s/
kubectl -n poke-app rollout status deployment/auth-db
kubectl -n poke-app rollout status deployment/auth
kubectl -n poke-app rollout status deployment/back
kubectl -n poke-app rollout status deployment/front
```

Si prefieres una limpieza todavía más agresiva del entorno local de Minikube, puedes parar y recrear el clúster con `minikube delete` y luego repetir la guía end-to-end desde el principio.

Script equivalente:

```bash
chmod +x scripts/cleanup-minikube.sh
./scripts/cleanup-minikube.sh
./scripts/cleanup-minikube.sh --purge
```

### Nota sobre el frontend

Para que la SPA no apunte a `localhost`, conviene compilar el frontend con:

- `VITE_API_BASE_URL=/api/v2`
- `VITE_AUTH_BASE_URL=/auth`

Eso hace que el navegador use el mismo host servido por el `Ingress`.
