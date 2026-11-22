// Carnet Mobile Money · version 0.12 : envoi des alertes WhatsApp (API WhatsApp Business Cloud de Meta).
//
// Appelée par l'application (jeton de l'utilisateur) :
//   { "action": "envoyer" }                         → envoie les alertes en attente (toutes, les plus anciennes d'abord)
//   { "action": "code", "numero": "6…", "nom": "…" } → envoie un code de confirmation à un nouveau destinataire
//
// Secrets à définir dans Supabase → Edge Functions → Secrets :
//   WHATSAPP_TOKEN     jeton d'accès permanent (utilisateur système Meta Business)
//   WHATSAPP_PHONE_ID  identifiant du numéro WhatsApp Business (Phone number ID)
//   WHATSAPP_LANGUE    langue des modèles (par défaut « fr »)
// Sans ces secrets, rien n'est envoyé : les alertes attendent puis expirent au bout de 24 heures.
//
// Modèles à faire valider chez Meta (noms exacts) : bilan_du_soir, ecart_caisse, alerte_compte,
// echeances_dettes (catégorie Utilitaire) et code_verification (catégorie Authentification).

import { createClient } from "jsr:@supabase/supabase-js@2";

const URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const TOKEN = Deno.env.get("WHATSAPP_TOKEN") ?? "";
const PHONE_ID = Deno.env.get("WHATSAPP_PHONE_ID") ?? "";
const LANGUE = Deno.env.get("WHATSAPP_LANGUE") ?? "fr";
const VERSION = Deno.env.get("WHATSAPP_API_VERSION") ?? "v21.0";

const admin = createClient(URL, SERVICE, { auth: { persistSession: false } });
const configure = TOKEN !== "" && PHONE_ID !== "";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function envoyerModele(numero: string, modele: string, params: string[], code?: string) {
  const components: unknown[] = [];
  if (params.length) components.push({ type: "body", parameters: params.map((t) => ({ type: "text", text: t })) });
  // Modèle d'authentification : le code va aussi dans le bouton « Copier le code ».
  if (code) components.push({ type: "button", sub_type: "url", index: "0", parameters: [{ type: "text", text: code }] });
  const r = await fetch(`https://graph.facebook.com/${VERSION}/${PHONE_ID}/messages`, {
    method: "POST",
    headers: { Authorization: `Bearer ${TOKEN}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      messaging_product: "whatsapp",
      to: "237" + numero,
      type: "template",
      template: { name: modele, language: { code: LANGUE }, components },
    }),
  });
  const body = await r.json().catch(() => ({}));
  if (!r.ok) return { ok: false, erreur: body?.error?.message ?? `HTTP ${r.status}` };
  return { ok: true, wamid: body?.messages?.[0]?.id ?? null };
}

async function envoyerEnAttente() {
  if (!configure) {
    const { count } = await admin.from("whatsapp_envoi").select("id", { count: "exact", head: true }).eq("statut", "en_attente");
    return { configure: false, envoyes: 0, en_attente: count ?? 0 };
  }
  const { data, error } = await admin.rpc("envois_a_faire", { p_limite: 50 });
  if (error) throw new Error(error.message);
  let envoyes = 0, echecs = 0;
  for (const e of data ?? []) {
    let res: { ok: boolean; wamid?: string | null; erreur?: string };
    try {
      res = await envoyerModele(e.numero, e.modele, (e.params ?? []) as string[]);
    } catch (x) {
      res = { ok: false, erreur: String(x) };
    }
    await admin.rpc("marquer_envoi", { p_id: e.id, p_ok: res.ok, p_wamid: res.wamid ?? null, p_erreur: res.erreur ?? null });
    if (res.ok) envoyes++; else echecs++;
  }
  return { configure: true, envoyes, echecs };
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ erreur: "POST attendu." }, 405);
  let body: Record<string, string> = {};
  try { body = await req.json(); } catch { /* corps vide */ }
  try {
    if (body.action === "code") {
      const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
      const { data: u, error: ue } = await admin.auth.getUser(jwt);
      if (ue || !u?.user) return json({ erreur: "Connectez-vous d'abord." }, 401);
      if (!configure) return json({ erreur: "L'envoi WhatsApp n'est pas encore activé par le service client. Réessayez plus tard." }, 503);
      const { data: code, error } = await admin.rpc("preparer_code", { p_user: u.user.id, p_numero: body.numero ?? "", p_nom: body.nom ?? "" });
      if (error) return json({ erreur: error.message }, 400);
      const num = String(body.numero ?? "").replace(/\D/g, "").slice(-9);
      const r = await envoyerModele(num, "code_verification", [String(code)], String(code));
      if (!r.ok) return json({ erreur: "WhatsApp n'a pas pu envoyer le code : " + r.erreur }, 502);
      return json({ ok: true });
    }
    return json(await envoyerEnAttente());
  } catch (x) {
    return json({ erreur: String(x instanceof Error ? x.message : x) }, 500);
  }
});
