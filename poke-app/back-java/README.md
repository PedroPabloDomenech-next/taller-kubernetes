# Poke Back Java

API en Java 21 que replica la superficie de `https://pokeapi.co/api/v2` y reenvía las peticiones a la PokeAPI real tras validar el token contra `auth`.

## Requisitos

- Java 21
- Maven 3.9+

## Ejecutar

```bash
cd poke-app/back-java
mvn spring-boot:run
```

La API quedará expuesta por defecto en:

```text
http://localhost:8080
```

Puedes configurar el servicio de auth con `AUTH_BASE_URL` o `Auth__BaseUrl`.
