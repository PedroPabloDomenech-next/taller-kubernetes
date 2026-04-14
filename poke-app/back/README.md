# Poke Back Java

API en Java 21 que replica la superficie de `https://pokeapi.co/api/v2` y reenvía las peticiones a la PokeAPI real tras validar el token contra `auth`.

## Requisitos

- Java 21
- Maven 3.9+
- Servicio `auth` disponible en `http://localhost:5190`

## Ejecutar

```bash
cd poke-app/back
SERVER_PORT=5180 AUTH_BASE_URL=http://localhost:5190 mvn spring-boot:run
```

La API quedará expuesta por defecto en:

```text
http://localhost:5180
```

Dentro del contenedor escucha en el puerto `8080`. En desarrollo local se suele publicar en `http://localhost:5180`.

Puedes configurar el servicio de auth con `AUTH_BASE_URL`.
