# Poke App Front

Frontend en React + Vite que consume el backend Java de `poke-app/back`.

## Requisitos

- Node.js 18 o superior
- Backend Java levantado en `http://localhost:5180`
- Servicio `auth` levantado en `http://localhost:5190`

## Ejecutar

```bash
cd poke-app/front
npm install
npm run dev
```

## Configuración

Puedes cambiar la URL base del backend con una variable de entorno:

```bash
VITE_API_BASE_URL=http://localhost:5180/api/v2
```

Y la del servicio de autenticación con:

```bash
VITE_AUTH_BASE_URL=http://localhost:5190/auth
```

## Funcionalidades

- Listado paginado de Pokémon
- Búsqueda por nombre
- Tarjetas con imagen oficial
- Gestión de estados de carga y error
