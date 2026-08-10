const MAX_BODY_BYTES = 8 * 1024;
const ALLOWED_FIELDS = {
  session: 40,
  appVersion: 40,
  androidVersion: 80,
  webViewVersion: 80,
  code: 80,
  service: 40,
  location: 160,
  detail: 240,
};

function clean(value, limit) {
  return String(value ?? "")
    .replace(/[\r\n\t]/g, " ")
    .replace(/\s{2,}/g, " ")
    .trim()
    .slice(0, limit);
}

export default function handler(request, response) {
  if (request.method === "GET") {
    return response.status(200).json({ ok: true, service: "ClearFeed diagnostics" });
  }
  if (request.method !== "POST") {
    response.setHeader("Allow", "GET, POST");
    return response.status(405).json({ ok: false });
  }

  const declaredLength = Number(request.headers["content-length"] || 0);
  if (declaredLength > MAX_BODY_BYTES || !request.body || typeof request.body !== "object") {
    return response.status(400).json({ ok: false });
  }

  const event = Object.fromEntries(
    Object.entries(ALLOWED_FIELDS).map(([field, limit]) => [field, clean(request.body[field], limit)]),
  );
  if (!event.code.startsWith("CF-") || !event.appVersion) {
    return response.status(400).json({ ok: false });
  }

  console.log("[ClearFeed]", JSON.stringify({ receivedAt: new Date().toISOString(), ...event }));
  return response.status(202).json({ ok: true });
}
