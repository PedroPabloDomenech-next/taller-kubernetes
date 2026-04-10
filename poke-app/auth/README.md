# PokeAuthApi

Microservicio de autenticación en .NET 8 para la `poke-app`, con PostgreSQL como persistencia y JWT para autenticación.

## Requisitos

- .NET SDK 8.0
- PostgreSQL disponible en `localhost:55432`

## Ejecutar

```bash
cd poke-app/auth
dotnet restore
dotnet run
```

La API quedará expuesta por defecto en:

```text
http://localhost:5190
```

## Endpoints

- `POST /auth/register`
- `POST /auth/login`
- `GET /auth/userinfo`
- `GET /health`

## Ejemplos

```bash
curl -X POST http://localhost:5190/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"ash@kanto.dev","firstName":"Ash","lastName":"Ketchum","password":"pikachu123"}'

curl -X POST http://localhost:5190/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"ash@kanto.dev","password":"pikachu123"}'

curl http://localhost:5190/auth/userinfo \
  -H "Authorization: Bearer <token>"
```

## Notas

- La contraseña se almacena hasheada con `PasswordHasher`.
- `GET /auth/userinfo` responde `401` si no hay token válido.
- La base de datos se crea automáticamente al arrancar si no existe.
