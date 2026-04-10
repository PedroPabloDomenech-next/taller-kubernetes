using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddCors(options =>
{
    options.AddDefaultPolicy(policy =>
    {
        policy
            .AllowAnyOrigin()
            .AllowAnyHeader()
            .AllowAnyMethod();
    });
});

builder.Services.AddDbContext<AuthDbContext>(options =>
{
    options.UseNpgsql(builder.Configuration.GetConnectionString("AuthDb"));
});

builder.Services.Configure<JwtOptions>(builder.Configuration.GetSection(JwtOptions.SectionName));
builder.Services.AddSingleton<IPasswordHasher<AuthUser>, PasswordHasher<AuthUser>>();

var jwtOptions = builder.Configuration.GetSection(JwtOptions.SectionName).Get<JwtOptions>()
    ?? throw new InvalidOperationException("JWT configuration is missing.");

var signingKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwtOptions.Key));

builder.Services
    .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidateAudience = true,
            ValidateIssuerSigningKey = true,
            ValidateLifetime = true,
            ValidIssuer = jwtOptions.Issuer,
            ValidAudience = jwtOptions.Audience,
            IssuerSigningKey = signingKey,
            ClockSkew = TimeSpan.FromSeconds(30)
        };
    });

builder.Services.AddAuthorization();

var app = builder.Build();

await EnsureDatabaseReadyAsync(app.Services, app.Logger, app.Lifetime.ApplicationStopping);

app.UseCors();
app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/", () => Results.Ok(new
{
    name = "PokeAuthApi",
    description = "Servicio de autenticación local para la poke-app",
    endpoints = new[]
    {
        "POST /auth/register",
        "POST /auth/login",
        "GET /auth/userinfo",
        "GET /health"
    }
}));

app.MapGet("/health", async (AuthDbContext dbContext, CancellationToken cancellationToken) =>
{
    var canConnect = await dbContext.Database.CanConnectAsync(cancellationToken);

    return canConnect
        ? Results.Ok(new { status = "ok" })
        : Results.Problem(statusCode: StatusCodes.Status503ServiceUnavailable, title: "Database unavailable");
});

app.MapPost("/auth/register", async (
    RegisterRequest request,
    AuthDbContext dbContext,
    IPasswordHasher<AuthUser> passwordHasher,
    CancellationToken cancellationToken) =>
{
    var validationErrors = ValidateRegisterRequest(request);

    if (validationErrors.Count > 0)
    {
        return Results.ValidationProblem(validationErrors);
    }

    var normalizedEmail = request.Email.Trim().ToUpperInvariant();
    var emailInUse = await dbContext.Users.AnyAsync(user => user.NormalizedEmail == normalizedEmail, cancellationToken);

    if (emailInUse)
    {
        return Results.ValidationProblem(new Dictionary<string, string[]>
        {
            ["email"] = ["Ya existe un usuario registrado con ese email."]
        });
    }

    var user = new AuthUser
    {
        Email = request.Email.Trim(),
        NormalizedEmail = normalizedEmail,
        FirstName = request.FirstName.Trim(),
        LastName = request.LastName.Trim(),
        CreatedAtUtc = DateTime.UtcNow
    };

    user.PasswordHash = passwordHasher.HashPassword(user, request.Password);

    dbContext.Users.Add(user);
    await dbContext.SaveChangesAsync(cancellationToken);

    return Results.Created($"/auth/users/{user.Id}", new UserInfoResponse(
        user.Id,
        user.Email,
        user.FirstName,
        user.LastName,
        user.CreatedAtUtc));
});

app.MapPost("/auth/login", async (
    LoginRequest request,
    AuthDbContext dbContext,
    IPasswordHasher<AuthUser> passwordHasher,
    CancellationToken cancellationToken) =>
{
    if (string.IsNullOrWhiteSpace(request.Email) || string.IsNullOrWhiteSpace(request.Password))
    {
        return Results.ValidationProblem(new Dictionary<string, string[]>
        {
            ["credentials"] = ["Debes indicar email y contraseña."]
        });
    }

    var normalizedEmail = request.Email.Trim().ToUpperInvariant();
    var user = await dbContext.Users.SingleOrDefaultAsync(item => item.NormalizedEmail == normalizedEmail, cancellationToken);

    if (user is null)
    {
        return Results.Unauthorized();
    }

    var verificationResult = passwordHasher.VerifyHashedPassword(user, user.PasswordHash, request.Password);

    if (verificationResult == PasswordVerificationResult.Failed)
    {
        return Results.Unauthorized();
    }

    var expiresAtUtc = DateTime.UtcNow.AddHours(jwtOptions.ExpirationHours);
    var token = CreateToken(user, jwtOptions, signingKey, expiresAtUtc);

    return Results.Ok(new LoginResponse(
        token,
        expiresAtUtc,
        new UserInfoResponse(user.Id, user.Email, user.FirstName, user.LastName, user.CreatedAtUtc)));
});

app.MapGet("/auth/userinfo", [Authorize] async (
    ClaimsPrincipal principal,
    AuthDbContext dbContext,
    CancellationToken cancellationToken) =>
{
    var userIdClaim = principal.FindFirstValue(JwtRegisteredClaimNames.Sub) ??
                      principal.FindFirstValue(ClaimTypes.NameIdentifier);

    if (!Guid.TryParse(userIdClaim, out var userId))
    {
        return Results.Unauthorized();
    }

    var user = await dbContext.Users.AsNoTracking().SingleOrDefaultAsync(item => item.Id == userId, cancellationToken);

    return user is null
        ? Results.Unauthorized()
        : Results.Ok(new UserInfoResponse(user.Id, user.Email, user.FirstName, user.LastName, user.CreatedAtUtc));
});

app.Run();

static Dictionary<string, string[]> ValidateRegisterRequest(RegisterRequest request)
{
    var errors = new Dictionary<string, string[]>();

    if (string.IsNullOrWhiteSpace(request.Email) || !request.Email.Contains('@'))
    {
        errors["email"] = ["Debes indicar un email válido."];
    }

    if (string.IsNullOrWhiteSpace(request.FirstName))
    {
        errors["firstName"] = ["Debes indicar el nombre."];
    }

    if (string.IsNullOrWhiteSpace(request.LastName))
    {
        errors["lastName"] = ["Debes indicar los apellidos."];
    }

    if (string.IsNullOrWhiteSpace(request.Password) || request.Password.Length < 8)
    {
        errors["password"] = ["La contraseña debe tener al menos 8 caracteres."];
    }

    return errors;
}

static string CreateToken(AuthUser user, JwtOptions options, SymmetricSecurityKey signingKey, DateTime expiresAtUtc)
{
    var credentials = new SigningCredentials(signingKey, SecurityAlgorithms.HmacSha256);
    var claims = new[]
    {
        new Claim(JwtRegisteredClaimNames.Sub, user.Id.ToString()),
        new Claim(JwtRegisteredClaimNames.Email, user.Email),
        new Claim(JwtRegisteredClaimNames.GivenName, user.FirstName),
        new Claim(JwtRegisteredClaimNames.FamilyName, user.LastName),
        new Claim(JwtRegisteredClaimNames.UniqueName, $"{user.FirstName} {user.LastName}"),
        new Claim(ClaimTypes.NameIdentifier, user.Id.ToString())
    };

    var token = new JwtSecurityToken(
        issuer: options.Issuer,
        audience: options.Audience,
        claims: claims,
        expires: expiresAtUtc,
        signingCredentials: credentials);

    return new JwtSecurityTokenHandler().WriteToken(token);
}

static async Task EnsureDatabaseReadyAsync(IServiceProvider services, ILogger logger, CancellationToken cancellationToken)
{
    const int maxAttempts = 20;

    for (var attempt = 1; attempt <= maxAttempts; attempt++)
    {
        try
        {
            using var scope = services.CreateScope();
            var dbContext = scope.ServiceProvider.GetRequiredService<AuthDbContext>();
            await dbContext.Database.EnsureCreatedAsync(cancellationToken);
            return;
        }
        catch (Exception ex) when (attempt < maxAttempts)
        {
            logger.LogWarning(ex, "PostgreSQL no está listo todavía. Reintentando ({Attempt}/{MaxAttempts})...", attempt, maxAttempts);
            await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken);
        }
    }

    throw new InvalidOperationException("No se pudo inicializar la base de datos de autenticación.");
}

sealed class AuthDbContext(DbContextOptions<AuthDbContext> options) : DbContext(options)
{
    public DbSet<AuthUser> Users => Set<AuthUser>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<AuthUser>(entity =>
        {
            entity.ToTable("users");
            entity.HasKey(item => item.Id);
            entity.HasIndex(item => item.NormalizedEmail).IsUnique();

            entity.Property(item => item.Email).HasMaxLength(256).IsRequired();
            entity.Property(item => item.NormalizedEmail).HasMaxLength(256).IsRequired();
            entity.Property(item => item.FirstName).HasMaxLength(100).IsRequired();
            entity.Property(item => item.LastName).HasMaxLength(150).IsRequired();
            entity.Property(item => item.PasswordHash).HasMaxLength(512).IsRequired();
            entity.Property(item => item.CreatedAtUtc).IsRequired();
        });
    }
}

sealed class AuthUser
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public string Email { get; set; } = string.Empty;
    public string NormalizedEmail { get; set; } = string.Empty;
    public string FirstName { get; set; } = string.Empty;
    public string LastName { get; set; } = string.Empty;
    public string PasswordHash { get; set; } = string.Empty;
    public DateTime CreatedAtUtc { get; set; }
}

sealed class JwtOptions
{
    public const string SectionName = "Jwt";

    public string Issuer { get; init; } = "PokeAuth";
    public string Audience { get; init; } = "PokeApp";
    public string Key { get; init; } = "super-secret-development-key-change-me";
    public int ExpirationHours { get; init; } = 8;
}

sealed record RegisterRequest(string Email, string FirstName, string LastName, string Password);

sealed record LoginRequest(string Email, string Password);

sealed record UserInfoResponse(Guid Id, string Email, string FirstName, string LastName, DateTime CreatedAtUtc);

sealed record LoginResponse(string AccessToken, DateTime ExpiresAtUtc, UserInfoResponse User);
