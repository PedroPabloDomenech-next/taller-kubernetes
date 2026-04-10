using System.Net.Http.Headers;

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

builder.Services.AddHttpClient("pokeapi", client =>
{
    client.BaseAddress = new Uri("https://pokeapi.co/");
    client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
});

var authBaseUrl = builder.Configuration["Auth:BaseUrl"] ?? "http://localhost:5190";

builder.Services.AddHttpClient("auth", client =>
{
    client.BaseAddress = new Uri(authBaseUrl);
    client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
});

var app = builder.Build();

app.UseCors();

app.MapGet("/", () => Results.Ok(new
{
    name = "PokeProxyApi",
    description = "Proxy local en .NET para la PokeAPI v2",
    upstream = "https://pokeapi.co/api/v2",
    endpoints = new[]
    {
        "/api/v2/{endpoint}",
        "/api/v2/{endpoint}/{id-or-name}"
    }
}));

app.MapGet("/health", () => Results.Ok(new
{
    status = "ok"
}));

app.MapMethods("/api/v2/{**pokePath}", new[] { "GET" }, async (
    string? pokePath,
    HttpContext context,
    IHttpClientFactory httpClientFactory,
    CancellationToken cancellationToken) =>
{
    var bearerToken = ExtractBearerToken(context.Request);

    if (string.IsNullOrWhiteSpace(bearerToken))
    {
        return Results.Unauthorized();
    }

    var authClient = httpClientFactory.CreateClient("auth");
    using var userInfoRequest = new HttpRequestMessage(HttpMethod.Get, "/auth/userinfo");
    userInfoRequest.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearerToken);

    using var userInfoResponse = await authClient.SendAsync(
        userInfoRequest,
        HttpCompletionOption.ResponseHeadersRead,
        cancellationToken);

    if (userInfoResponse.StatusCode == System.Net.HttpStatusCode.Unauthorized)
    {
        return Results.Unauthorized();
    }

    if (!userInfoResponse.IsSuccessStatusCode)
    {
        return Results.Problem(
            title: "No se pudo validar el usuario autenticado.",
            statusCode: StatusCodes.Status502BadGateway);
    }

    if (string.IsNullOrWhiteSpace(pokePath))
    {
        return Results.BadRequest(new
        {
            error = "Debes indicar una ruta de la PokeAPI v2."
        });
    }

    var client = httpClientFactory.CreateClient("pokeapi");
    var upstreamUri = BuildUpstreamUri(context.Request, pokePath);
    using var upstreamRequest = new HttpRequestMessage(HttpMethod.Get, upstreamUri);
    using var upstreamResponse = await client.SendAsync(
        upstreamRequest,
        HttpCompletionOption.ResponseHeadersRead,
        cancellationToken);

    var responseBody = await upstreamResponse.Content.ReadAsByteArrayAsync(cancellationToken);
    var contentType = upstreamResponse.Content.Headers.ContentType?.ToString() ?? "application/json";

    foreach (var header in upstreamResponse.Headers)
    {
        context.Response.Headers[header.Key] = header.Value.ToArray();
    }

    foreach (var header in upstreamResponse.Content.Headers)
    {
        context.Response.Headers[header.Key] = header.Value.ToArray();
    }

    context.Response.Headers.Remove("transfer-encoding");
    context.Response.Headers.Remove("content-length");
    context.Response.StatusCode = (int)upstreamResponse.StatusCode;

    return Results.File(responseBody, contentType);
});

app.Run();

static string? ExtractBearerToken(HttpRequest request)
{
    if (!request.Headers.TryGetValue("Authorization", out var authorizationHeader))
    {
        return null;
    }

    var headerValue = authorizationHeader.ToString();

    const string bearerPrefix = "Bearer ";

    return headerValue.StartsWith(bearerPrefix, StringComparison.OrdinalIgnoreCase)
        ? headerValue[bearerPrefix.Length..].Trim()
        : null;
}

static string BuildUpstreamUri(HttpRequest request, string pokePath)
{
    var normalizedPath = pokePath.TrimStart('/');
    var upstreamPath = $"api/v2/{normalizedPath}";
    var queryString = request.QueryString.HasValue ? request.QueryString.Value : string.Empty;

    return $"{upstreamPath}{queryString}";
}
