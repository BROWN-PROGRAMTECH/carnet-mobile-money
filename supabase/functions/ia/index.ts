// Carnet Mobile Money · version 0.13 : agent IA (Claude, d'Anthropic).
//
// Appelée par l'application avec le jeton de l'utilisateur :
//   { action: "lire_sms", operateur, texte }       → règle de lecture d'un SMS inconnu (texte déjà masqué)
//   { action: "classer", operations, categories }   → catégorie proposée pour des opérations à classer
//   { action: "question", question, contexte, historique } → réponse en français sur ses chiffres
// Les données personnelles sont masquées par le téléphone avant l'envoi (numéros, noms, ID → jetons).
//
// Secret à définir dans Supabase → Edge Functions → Secrets : ANTHROPIC_API_KEY (console.anthropic.com).
// Facultatif : IA_MODELE (par défaut claude-haiku-5-5), IA_MODELE_QUESTION (par défaut le même).

import { createClient } from "jsr:@supabase/supabase-js@2";

const URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const CLE = Deno.env.get("ANTHROPIC_API_KEY") ?? "";
const MODELE = Deno.env.get("IA_MODELE") ?? "claude-haiku-5-5";
const MODELE_QUESTION = Deno.env.get("IA_MODELE_QUESTION") ?? MODELE;
const admin = createClient(URL, SERVICE, { auth: { persistSession: false } });

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function claude(modele: string, system: string, messages: { role: string; content: string }[], maxTokens: number) {
  const r = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": CLE, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({ model: modele, max_tokens: maxTokens, system, messages }),
  });
  const b = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error("IA indisponible : " + (b?.error?.message ?? `HTTP ${r.status}`));
  return (b.content ?? []).filter((c: { type: string }) => c.type === "text").map((c: { text: string }) => c.text).join("");
}

/** Premier objet ou tableau JSON trouvé dans la réponse. */
function extraireJson(t: string): unknown {
  const debut = t.search(/[[{]/);
  if (debut < 0) throw new Error("Réponse de l'IA illisible.");
  const fin = Math.max(t.lastIndexOf("}"), t.lastIndexOf("]"));
  return JSON.parse(t.slice(debut, fin + 1));
}

/** Même lecture des montants que l'application : « 12 640,53 », « 12640.53 », « 1.000 ». */
function montant(s: string | undefined): number | null {
  if (!s) return null;
  let t = s.replace(/[\s  ]/g, "");
  const dec = t.match(/^(.*?)[.,](\d{1,2})$/);
  let cents = 0;
  if (dec && dec[1] !== "") { cents = parseInt(dec[2].length === 1 ? dec[2] + "0" : dec[2]); t = dec[1]; }
  t = t.replace(/[.,]/g, "");
  if (!/^\d{1,12}$/.test(t)) return null;
  return parseInt(t) * 100 + cents;
}

function capture(motif: string, texte: string): number | null {
  if (!motif) return null;
  const m = new RegExp(motif, "i").exec(texte);
  return m && m[1] ? montant(m[1]) : null;
}

const SYS_SMS = `Tu écris des règles de lecture de SMS de mobile money et de banques au Cameroun (Orange Money, MTN MoMo, banques).
On te donne un SMS normalisé (accents retirés), où les données personnelles sont remplacées par <TEL>, <NOM>, <ID>, <NUMERO>.
Réponds UNIQUEMENT avec un objet JSON :
{"comprend": true|false, "libelle": "nom court de l'opération en français (ex. Recharge carte virtuelle)",
 "sens": -1 (l'argent sort du compte) | 1 (l'argent entre) | 0 (simple information ou consultation de solde),
 "signature": "expression régulière (syntaxe Java/JavaScript, insensible à la casse) qui reconnaît CE TYPE de SMS et pas les autres ; mots fixes du SMS, sans les montants",
 "montant": "expression dont le groupe 1 capture le montant de l'opération (chiffres, espaces, point ou virgule), ou chaîne vide",
 "solde": "expression dont le groupe 1 capture le nouveau solde du compte, ou chaîne vide",
 "frais": "expression dont le groupe 1 capture les frais, ou chaîne vide",
 "explication": "une phrase en français"}
N'utilise pas les jetons <TEL>, <NOM>, <ID>, <NUMERO> dans les expressions. Si le SMS n'est pas un mouvement d'argent ni un solde (publicité, information), "comprend": false.`;

const SYS_CLASSER = `Tu classes des opérations d'un petit commerce au Cameroun dans des catégories.
On te donne une liste d'opérations (opérateur, type, sens +1 entrée / -1 sortie, montant en FCFA, jour, heure, motif masqué) et la liste des catégories permises avec leur sens.
Réponds UNIQUEMENT avec un tableau JSON : [{"i": numéro de l'opération, "categorie": "CODE", "confiance": 0 à 100}].
Choisis seulement une catégorie compatible avec le sens. Si tu ne sais pas, mets une confiance basse (moins de 50). Sois prudent : une confiance au-dessus de 90 seulement si c'est évident.`;

const SYS_QUESTION = `Tu es l'assistant de « Carnet Mobile Money », une application qui tient la caisse et les finances d'un commerçant ou d'un particulier au Cameroun à partir de ses SMS Orange Money, MTN MoMo et bancaires.
Réponds en français simple, court (5 phrases au plus, ou une petite liste), avec les montants en FCFA.
Utilise uniquement les chiffres du contexte fourni ; si l'information manque, dis-le et indique où la trouver dans l'application (onglets Caisse, À classer, Finances, Exporter, Compte).
Garde tels quels les jetons comme « Client 2 » ou « Personne 1 » (le téléphone remettra les vrais noms).
Tu peux donner des conseils de gestion simples et prudents ; jamais de conseil juridique ou fiscal précis.`;

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ erreur: "POST attendu." }, 405);
  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: u, error: ue } = await admin.auth.getUser(jwt);
  if (ue || !u?.user) return json({ erreur: "Connectez-vous d'abord." }, 401);
  if (!CLE) return json({ erreur: "L'assistant IA n'est pas encore activé par le service client. Réessayez plus tard." }, 503);
  let b: Record<string, unknown> = {};
  try { b = await req.json(); } catch { /* vide */ }

  try {
    if (b.action === "lire_sms") {
      const texte = String(b.texte ?? "").slice(0, 1000);
      const op = ["OM", "MOMO"].includes(String(b.operateur)) || String(b.operateur ?? "").startsWith("BQ:") ? String(b.operateur) : null;
      if (texte.length < 15) return json({ erreur: "SMS trop court." }, 400);
      const { error: qe } = await admin.rpc("consommer_ia", { p_user: u.user.id, p_nature: "sms" });
      if (qe) return json({ erreur: qe.message }, 429);
      const rep = extraireJson(await claude(MODELE, SYS_SMS, [{ role: "user", content: `Opérateur : ${op ?? "inconnu"}\nSMS : ${texte}` }], 800)) as Record<string, unknown>;
      if (!rep.comprend) return json({ comprend: false, explication: rep.explication ?? "Ce SMS n'est pas un mouvement d'argent." });
      // Vérification sur l'exemple : la règle doit reconnaître le SMS et en tirer un montant ou un solde.
      const sig = String(rep.signature ?? "");
      // Une règle ne doit pas dépendre des jetons de masquage (absents des vrais SMS).
      if ([rep.signature, rep.montant, rep.solde, rep.frais].some((x) => /<(TEL|NOM|ID|NUMERO)>/.test(String(x ?? "")))) {
        return json({ comprend: false, explication: "Règle proposée invalide (données masquées)." });
      }
      try { if (!sig || !new RegExp(sig, "i").test(texte)) throw new Error(); } catch { return json({ comprend: false, explication: "Règle proposée invalide." }); }
      let m: number | null = null, s: number | null = null;
      try { m = capture(String(rep.montant ?? ""), texte); s = capture(String(rep.solde ?? ""), texte); capture(String(rep.frais ?? ""), texte); }
      catch { return json({ comprend: false, explication: "Règle proposée invalide." }); }
      if (m === null && s === null) return json({ comprend: false, explication: "L'IA n'a pas trouvé de montant." });
      const sens = Number(rep.sens) > 0 ? 1 : Number(rep.sens) < 0 ? -1 : 0;
      const { data: regle, error } = await admin.rpc("enregistrer_regle_ia", {
        p_user: u.user.id,
        p_regle: { operateur: op, signature: sig, montant: rep.montant ?? "", solde: rep.solde ?? "", frais: rep.frais ?? "", sens,
                   libelle: String(rep.libelle ?? "Opération").slice(0, 60), exemple: texte, explication: rep.explication ?? "" },
      });
      if (error) throw new Error(error.message);
      return json({ comprend: true, regle, montant: m, solde: s, explication: rep.explication ?? "" });
    }

    if (b.action === "classer") {
      const ops = (Array.isArray(b.operations) ? b.operations : []).slice(0, 40);
      const cats = Array.isArray(b.categories) ? b.categories : [];
      if (!ops.length) return json({ propositions: [] });
      const { error: qe } = await admin.rpc("consommer_ia", { p_user: u.user.id, p_nature: "question" });
      if (qe) return json({ erreur: qe.message }, 429);
      const rep = extraireJson(await claude(MODELE, SYS_CLASSER, [{ role: "user", content:
        "Catégories permises :\n" + JSON.stringify(cats) + "\n\nOpérations :\n" + JSON.stringify(ops) }], 2000));
      const codes = new Set(cats.map((c: { code: string }) => c.code));
      const props = (Array.isArray(rep) ? rep : []).filter((p: { categorie: string }) => codes.has(p.categorie));
      return json({ propositions: props });
    }

    if (b.action === "question") {
      const question = String(b.question ?? "").slice(0, 500).trim();
      if (!question) return json({ erreur: "Posez une question." }, 400);
      const { data: q, error: qe } = await admin.rpc("consommer_ia", { p_user: u.user.id, p_nature: "question" });
      if (qe) return json({ erreur: qe.message }, 429);
      const histo = (Array.isArray(b.historique) ? b.historique : []).slice(-6)
        .map((h: { role: string; texte: string }) => ({ role: h.role === "assistant" ? "assistant" : "user", content: String(h.texte ?? "").slice(0, 1500) }))
        .filter((h: { content: string }) => h.content);
      const messages = [...histo, { role: "user", content: "Mes chiffres :\n" + String(b.contexte ?? "").slice(0, 12000) + "\n\nMa question : " + question }];
      // L'API demande des tours alternés commençant par l'utilisateur.
      while (messages.length && messages[0].role !== "user") messages.shift();
      const reponse = await claude(MODELE_QUESTION, SYS_QUESTION, messages, 700);
      return json({ reponse, questions: q?.questions, quota: q?.quota });
    }
    return json({ erreur: "Action inconnue." }, 400);
  } catch (x) {
    return json({ erreur: String(x instanceof Error ? x.message : x) }, 500);
  }
});
