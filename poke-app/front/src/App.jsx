import { useEffect, useState } from "react";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? (import.meta.env.DEV ? "/api/v2" : "http://localhost:5180/api/v2");
const AUTH_BASE_URL = import.meta.env.VITE_AUTH_BASE_URL ?? (import.meta.env.DEV ? "/auth" : "http://localhost:5190/auth");
const PAGE_SIZE = 12;
const TOKEN_STORAGE_KEY = "poke_auth_token";

function App() {
  const [token, setToken] = useState(() => window.localStorage.getItem(TOKEN_STORAGE_KEY) ?? "");
  const [currentUser, setCurrentUser] = useState(null);
  const [authView, setAuthView] = useState("login");
  const [authLoading, setAuthLoading] = useState(false);
  const [authError, setAuthError] = useState("");
  const [authNotice, setAuthNotice] = useState("");
  const [loginForm, setLoginForm] = useState({
    email: "",
    password: "",
  });
  const [registerForm, setRegisterForm] = useState({
    email: "",
    firstName: "",
    lastName: "",
    password: "",
  });
  const [pokemons, setPokemons] = useState([]);
  const [count, setCount] = useState(0);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");
  const [submittedSearch, setSubmittedSearch] = useState("");
  const [selectedType, setSelectedType] = useState("");
  const [submittedType, setSubmittedType] = useState("");
  const [pokemonTypes, setPokemonTypes] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let ignore = false;

    async function loadCurrentUser() {
      if (!token) {
        setCurrentUser(null);
        setLoading(false);
        return;
      }

      setLoading(true);
      setError("");

      try {
        const response = await fetch(`${AUTH_BASE_URL}/userinfo`, {
          headers: {
            Authorization: `Bearer ${token}`,
          },
        });

        if (response.status === 401) {
          clearSession();
          if (!ignore) {
            setAuthError("Tu sesión ya no es válida. Vuelve a iniciar sesión.");
          }
          return;
        }

        if (!response.ok) {
          throw new Error("No se pudo comprobar la sesión actual.");
        }

        const user = await response.json();

        if (!ignore) {
          setCurrentUser(user);
        }
      } catch (err) {
        if (!ignore) {
          setCurrentUser(null);
          setLoading(false);
          setError(err instanceof Error ? err.message : "Se ha producido un error inesperado.");
        }
      }
    }

    loadCurrentUser();

    return () => {
      ignore = true;
    };
  }, [token]);

  useEffect(() => {
    if (!token || !currentUser) {
      return;
    }

    let ignore = false;

    async function loadPokemonTypes() {
      try {
        const response = await fetch(`${API_BASE_URL}/type`, {
          headers: {
            Authorization: `Bearer ${token}`,
          },
        });

        if (response.status === 401) {
          clearSession();
          return;
        }

        if (!response.ok) {
          return;
        }

        const data = await response.json();
        const filteredTypes = Array.isArray(data.results)
          ? data.results.filter((type) => !["shadow", "unknown"].includes(type.name))
          : [];

        if (!ignore) {
          setPokemonTypes(filteredTypes);
        }
      } catch {
        if (!ignore) {
          setPokemonTypes([]);
        }
      }
    }

    loadPokemonTypes();

    return () => {
      ignore = true;
    };
  }, [currentUser, token]);

  useEffect(() => {
    if (!token || !currentUser) {
      return;
    }

    let ignore = false;

    async function loadPokemons() {
      setLoading(true);
      setError("");

      try {
        const normalizedSearch = submittedSearch.trim().toLowerCase();
        const normalizedType = submittedType.trim().toLowerCase();

        if (normalizedSearch) {
          const response = await fetch(`${API_BASE_URL}/pokemon/${normalizedSearch}`, {
            headers: {
              Authorization: `Bearer ${token}`,
            },
          });

          if (response.status === 404) {
            throw new Error("No se ha encontrado ningún Pokémon con ese nombre.");
          }

          if (response.status === 401) {
            clearSession();
            throw new Error("La sesión ha expirado. Vuelve a iniciar sesión.");
          }

          if (!response.ok) {
            throw new Error("No se pudo cargar el Pokémon solicitado.");
          }

          const pokemon = await response.json();
          const matchesType =
            !normalizedType ||
            pokemon.types?.some((entry) => entry.type?.name?.toLowerCase() === normalizedType);

          if (!matchesType) {
            throw new Error(`"${formatPokemonName(pokemon.name)}" no pertenece al tipo ${formatPokemonName(normalizedType)}.`);
          }

          if (!ignore) {
            setPokemons([
              {
                id: pokemon.id,
                name: pokemon.name,
                image: getPokemonImage(pokemon.id),
              },
            ]);
            setCount(1);
          }

          return;
        }

        if (normalizedType) {
          const response = await fetch(`${API_BASE_URL}/type/${normalizedType}`, {
            headers: {
              Authorization: `Bearer ${token}`,
            },
          });

          if (response.status === 404) {
            throw new Error("No se ha encontrado ningún tipo de Pokémon con ese nombre.");
          }

          if (response.status === 401) {
            clearSession();
            throw new Error("La sesión ha expirado. Vuelve a iniciar sesión.");
          }

          if (!response.ok) {
            throw new Error("No se pudo cargar el filtro por tipo.");
          }

          const data = await response.json();
          const allPokemons = Array.isArray(data.pokemon)
            ? data.pokemon.map((entry) => {
                const id = extractPokemonId(entry.pokemon.url);

                return {
                  id,
                  name: entry.pokemon.name,
                  image: getPokemonImage(id),
                };
              })
            : [];
          const start = (page - 1) * PAGE_SIZE;
          const paginated = allPokemons.slice(start, start + PAGE_SIZE);

          if (!ignore) {
            setPokemons(paginated);
            setCount(allPokemons.length);
          }

          return;
        }

        const offset = (page - 1) * PAGE_SIZE;
        const response = await fetch(`${API_BASE_URL}/pokemon?limit=${PAGE_SIZE}&offset=${offset}`, {
          headers: {
            Authorization: `Bearer ${token}`,
          },
        });

        if (response.status === 401) {
          clearSession();
          throw new Error("La sesión ha expirado. Vuelve a iniciar sesión.");
        }

        if (!response.ok) {
          throw new Error("No se pudo cargar el listado de Pokémon.");
        }

        const data = await response.json();
        const mapped = data.results.map((pokemon) => {
          const id = extractPokemonId(pokemon.url);

          return {
            id,
            name: pokemon.name,
            image: getPokemonImage(id),
          };
        });

        if (!ignore) {
          setPokemons(mapped);
          setCount(data.count);
        }
      } catch (err) {
        if (!ignore) {
          setPokemons([]);
          setCount(0);
          setError(err instanceof Error ? err.message : "Se ha producido un error inesperado.");
        }
      } finally {
        if (!ignore) {
          setLoading(false);
        }
      }
    }

    loadPokemons();

    return () => {
      ignore = true;
    };
  }, [currentUser, page, submittedSearch, submittedType, token]);

  const totalPages = Math.max(1, Math.ceil(count / PAGE_SIZE));
  const hasSearch = submittedSearch.trim().length > 0;
  const hasTypeFilter = submittedType.trim().length > 0;
  const shouldShowPagination = !hasSearch;

  function handleSubmit(event) {
    event.preventDefault();
    setPage(1);
    setSubmittedSearch(search);
    setSubmittedType(selectedType);
  }

  function handleReset() {
    setSearch("");
    setSubmittedSearch("");
    setSelectedType("");
    setSubmittedType("");
    setPage(1);
  }

  function updateLoginForm(field, value) {
    setLoginForm((current) => ({
      ...current,
      [field]: value,
    }));
  }

  function updateRegisterForm(field, value) {
    setRegisterForm((current) => ({
      ...current,
      [field]: value,
    }));
  }

  async function handleLogin(event) {
    event.preventDefault();
    setAuthLoading(true);
    setAuthError("");
    setAuthNotice("");

    try {
      const response = await fetch(`${AUTH_BASE_URL}/login`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(loginForm),
      });

      if (response.status === 401) {
        throw new Error("Email o contraseña incorrectos.");
      }

      if (!response.ok) {
        throw new Error("No se pudo iniciar sesión.");
      }

      const data = await response.json();
      persistSession(data.accessToken, data.user);
      setLoginForm({
        email: "",
        password: "",
      });
    } catch (err) {
      setAuthError(err instanceof Error ? err.message : "Se ha producido un error inesperado.");
    } finally {
      setAuthLoading(false);
    }
  }

  async function handleRegister(event) {
    event.preventDefault();
    setAuthLoading(true);
    setAuthError("");
    setAuthNotice("");

    try {
      const response = await fetch(`${AUTH_BASE_URL}/register`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(registerForm),
      });

      const responseBody = await readJsonSafely(response);

      if (!response.ok) {
        throw new Error(extractApiError(responseBody, "No se pudo registrar el usuario."));
      }

      setRegisterForm({
        email: "",
        firstName: "",
        lastName: "",
        password: "",
      });
      setAuthView("login");
      setLoginForm((current) => ({
        ...current,
        email: registerForm.email,
      }));
      setAuthNotice("Usuario registrado. Ahora ya puedes iniciar sesión.");
    } catch (err) {
      setAuthError(err instanceof Error ? err.message : "Se ha producido un error inesperado.");
    } finally {
      setAuthLoading(false);
    }
  }

  function persistSession(nextToken, user) {
    window.localStorage.setItem(TOKEN_STORAGE_KEY, nextToken);
    setToken(nextToken);
    setCurrentUser(user);
    setPage(1);
    setSearch("");
    setSubmittedSearch("");
    setSelectedType("");
    setSubmittedType("");
    setAuthError("");
    setAuthNotice("");
  }

  function clearSession() {
    window.localStorage.removeItem(TOKEN_STORAGE_KEY);
    setToken("");
    setCurrentUser(null);
    setPokemons([]);
    setCount(0);
    setSearch("");
    setSubmittedSearch("");
    setSelectedType("");
    setSubmittedType("");
    setPokemonTypes([]);
  }

  function handleLogout() {
    clearSession();
    setAuthNotice("Sesión cerrada.");
    setAuthView("login");
  }

  if (!token || !currentUser) {
    return (
      <main className="auth-shell">
        <section className="auth-hero">
          <p className="eyebrow">Poke App</p>
          <h1>Entrenador, tu clúster está listo.</h1>
          <p className="hero-copy">
            Inicia sesión o crea una cuenta para entrar en una Pokédex desplegada desde Kubernetes y servida por ArgoCD.
          </p>
        </section>

        <section className="auth-card">
          <div className="auth-switch">
            <button
              type="button"
              className={authView === "login" ? "active" : ""}
              onClick={() => {
                setAuthView("login");
                setAuthError("");
                setAuthNotice("");
              }}
            >
              Login
            </button>
            <button
              type="button"
              className={authView === "register" ? "active" : ""}
              onClick={() => {
                setAuthView("register");
                setAuthError("");
                setAuthNotice("");
              }}
            >
              Registro
            </button>
          </div>

          {authError ? <div className="feedback error">{authError}</div> : null}
          {authNotice ? <div className="feedback success">{authNotice}</div> : null}

          {authView === "login" ? (
            <form className="auth-form" onSubmit={handleLogin}>
              <label htmlFor="login-email">Email</label>
              <input
                id="login-email"
                type="email"
                value={loginForm.email}
                onChange={(event) => updateLoginForm("email", event.target.value)}
                placeholder="ash@kanto.dev"
                autoComplete="email"
              />

              <label htmlFor="login-password">Contraseña</label>
              <input
                id="login-password"
                type="password"
                value={loginForm.password}
                onChange={(event) => updateLoginForm("password", event.target.value)}
                placeholder="Mínimo 8 caracteres"
                autoComplete="current-password"
              />

              <button type="submit" disabled={authLoading}>
                {authLoading ? "Entrando..." : "Iniciar sesión"}
              </button>
            </form>
          ) : (
            <form className="auth-form" onSubmit={handleRegister}>
              <label htmlFor="register-name">Nombre</label>
              <input
                id="register-name"
                type="text"
                value={registerForm.firstName}
                onChange={(event) => updateRegisterForm("firstName", event.target.value)}
                placeholder="Misty"
                autoComplete="given-name"
              />

              <label htmlFor="register-last-name">Apellidos</label>
              <input
                id="register-last-name"
                type="text"
                value={registerForm.lastName}
                onChange={(event) => updateRegisterForm("lastName", event.target.value)}
                placeholder="Williams"
                autoComplete="family-name"
              />

              <label htmlFor="register-email">Email</label>
              <input
                id="register-email"
                type="email"
                value={registerForm.email}
                onChange={(event) => updateRegisterForm("email", event.target.value)}
                placeholder="misty@kanto.dev"
                autoComplete="email"
              />

              <label htmlFor="register-password">Contraseña</label>
              <input
                id="register-password"
                type="password"
                value={registerForm.password}
                onChange={(event) => updateRegisterForm("password", event.target.value)}
                placeholder="Mínimo 8 caracteres"
                autoComplete="new-password"
              />

              <button type="submit" disabled={authLoading}>
                {authLoading ? "Registrando..." : "Crear cuenta"}
              </button>
            </form>
          )}

          <div className="summary-card">
            <span>Servicios</span>
            <code>auth: {AUTH_BASE_URL}</code>
            <code>api: {API_BASE_URL}</code>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="app-shell">
      <section className="hero">
        <h1 className="eyebrow">Pokédex local</h1>
        <div className="hero-topbar">
          <div className="hero-profile" aria-label="Perfil del usuario autenticado">
            <div className="hero-profile-avatar" aria-hidden="true">
              <svg viewBox="0 0 24 24" role="presentation">
                <path d="M12 12a4 4 0 1 0-4-4 4 4 0 0 0 4 4Zm0 2c-3.86 0-7 2.24-7 5v1h14v-1c0-2.76-3.14-5-7-5Z" />
              </svg>
            </div>
            <div className="hero-profile-copy">
              <p className="hero-user-label">Usuario autenticado</p>
              <strong>{formatDisplayName(currentUser)}</strong>
              <p className="hero-user-email">{currentUser.email}</p>
            </div>
          </div>
          <button type="button" className="logout-button" onClick={handleLogout}>
            Cerrar sesión
          </button>
        </div>
      </section>

      <section className="toolbar">
        <form className="search-form" onSubmit={handleSubmit}>
          <label className="search-label" htmlFor="pokemon-search">
            Buscar y filtrar
          </label>
          <div className="search-row">
            <input
              id="pokemon-search"
              type="text"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Ejemplo: pikachu"
            />
            <select value={selectedType} onChange={(event) => setSelectedType(event.target.value)} aria-label="Filtrar por tipo">
              <option value="">Todos los tipos</option>
              {pokemonTypes.map((type) => (
                <option key={type.name} value={type.name}>
                  {formatPokemonName(type.name)}
                </option>
              ))}
            </select>
            <button type="submit">Buscar</button>
            <button type="button" className="secondary" onClick={handleReset}>
              Limpiar
            </button>
          </div>
        </form>

        <div className="summary-card">
          <span>API base</span>
          <code>{API_BASE_URL}</code>
          <span>Auth base</span>
          <code>{AUTH_BASE_URL}</code>
        </div>
      </section>

      {error ? <div className="feedback error">{error}</div> : null}
      {loading ? <div className="feedback">Cargando Pokémon...</div> : null}

      {!loading && !error ? (
        <>
          <section className="results-meta">
            <p>
              {hasSearch
                ? `Resultado para "${submittedSearch}"${hasTypeFilter ? ` en tipo ${formatPokemonName(submittedType)}` : ""}.`
                : hasTypeFilter
                  ? `Tipo ${formatPokemonName(submittedType)}. Página ${page} de ${totalPages}. Total de Pokémon: ${count}.`
                  : `Página ${page} de ${totalPages}. Total de Pokémon: ${count}.`}
            </p>
          </section>

          <section className="grid">
            {pokemons.map((pokemon) => (
              <article key={`${pokemon.name}-${pokemon.id ?? "unknown"}`} className="card">
                <div className="sprite-wrap">
                  {pokemon.image ? (
                    <img src={pokemon.image} alt={pokemon.name} loading="lazy" />
                  ) : (
                    <div className="sprite-fallback">?</div>
                  )}
                </div>
                <p className="pokemon-id">{pokemon.id ? `#${String(pokemon.id).padStart(4, "0")}` : "Sin ID"}</p>
                <h2>{formatPokemonName(pokemon.name)}</h2>
              </article>
            ))}
          </section>

          {shouldShowPagination ? (
            <nav className="pagination" aria-label="Paginación de Pokémon">
              <button type="button" onClick={() => setPage((current) => Math.max(1, current - 1))} disabled={page === 1}>
                Anterior
              </button>
              <span>
                Página <strong>{page}</strong> / {totalPages}
              </span>
              <button
                type="button"
                onClick={() => setPage((current) => Math.min(totalPages, current + 1))}
                disabled={page >= totalPages}
              >
                Siguiente
              </button>
            </nav>
          ) : null}
        </>
      ) : null}
    </main>
  );
}

async function readJsonSafely(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

function extractApiError(responseBody, fallbackMessage) {
  if (!responseBody || typeof responseBody !== "object") {
    return fallbackMessage;
  }

  if (typeof responseBody.title === "string" && responseBody.title.trim()) {
    return responseBody.title;
  }

  if (responseBody.errors && typeof responseBody.errors === "object") {
    const firstError = Object.values(responseBody.errors).flat().find(Boolean);

    if (firstError) {
      return firstError;
    }
  }

  return fallbackMessage;
}

function extractPokemonId(url) {
  const match = url.match(/\/pokemon\/(\d+)\/?$/);
  return match ? Number(match[1]) : null;
}

function getPokemonImage(id) {
  if (!id) {
    return "";
  }

  return `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${id}.png`;
}

function formatPokemonName(name) {
  if (!name) {
    return "";
  }

  return name
    .split("-")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function formatDisplayName(user) {
  if (!user) {
    return "";
  }

  const fullName = [user.firstName, user.lastName].filter(Boolean).join(" ").trim();
  return fullName || user.email;
}

export default App;
