// No transcripts, entity values, logs or business API credentials enter this service.
export const allowed = new Set(["a", "add", "again", "already", "am", "amount", "an", "and", "are", "assistant", "balance", "be", "been", "but", "byl", "byla", "bylo", "can", "check", "client", "complete", "completed", "could", "customer", "da", "daily", "davai", "davay", "deili", "deyli", "did", "digits", "dlya", "do", "dobav", "dolg", "don", "done", "dont", "due", "enter", "est", "eta", "eti", "eto", "etot", "etu", "find", "finish", "finished", "for", "four", "from", "gde", "get", "gotov", "gotova", "gotovo", "had", "has", "have", "hochu", "i", "ili", "in", "is", "it", "job", "kakaya", "kakoy", "kem", "klient", "klientu", "komu", "kto", "kvitantsii", "kvitantsiya", "kvitantsiyu", "last", "let", "lets", "locate", "look", "looking", "master", "me", "menya", "message", "mine", "mne", "moy", "moya", "mozhesh", "mozhet", "my", "na", "nado", "najdi", "nash", "naydi", "ne", "need", "neoplacheno", "net", "ni", "no", "nomer", "nomera", "not", "number", "numbers", "nuzhno", "on", "ona", "ono", "oplacheno", "or", "order", "orders", "otchet", "otchete", "otprav", "otpravi", "otpravit", "otpravlyat", "our", "paid", "phone", "please", "pod", "poischi", "poiski", "pokazhi", "poslednie", "pozhaluysta", "price", "prover", "proveri", "put", "rabota", "ready", "receipt", "receipts", "record", "register", "report", "reports", "resit", "resita", "resitu", "save", "sdelal", "sdelala", "search", "see", "segodnya", "send", "sending", "show", "skazhi", "sms", "sohrani", "sokhranen", "sokhranena", "sokhrani", "soobschenie", "summa", "t", "tailor", "tebe", "telefon", "telefona", "telephone", "tell", "text", "that", "the", "these", "this", "those", "ticket", "tickets", "to", "today", "total", "tsifry", "ty", "under", "unknown", "unpaid", "up", "us", "uzhe", "v", "vchera", "vnesi", "want", "was", "we", "were", "where", "which", "who", "whose", "will", "with", "without", "work", "worker", "would", "write", "ya", "yes", "yesterday", "you", "your", "za", "zakaz", "zakaza", "zakonchen", "zakonchil", "zakonchila", "zapisan", "zapisana", "zapisano", "zapisat", "zapishi"]);
const waits=new Set(["command","worker","receipt","number","sms","pickup"]);
export function validPayload(p) {
  if(!p || Object.keys(p).sort().join()!=="hasCurrentWorker,numberKinds,tokens,waiting")return false;
  if(!Array.isArray(p.tokens)||p.tokens.length>96||!waits.has(p.waiting)||typeof p.hasCurrentWorker!=="boolean")return false;
  if(!p.tokens.every(t=>typeof t==="string"&&(allowed.has(t)||/^[NW][1-9][0-9]?$/.test(t))))return false;
  if(!p.numberKinds||Array.isArray(p.numberKinds)||typeof p.numberKinds!=="object")return false;
  if(Object.entries(p.numberKinds).some(([k,v])=>!/^N[1-9][0-9]?$/.test(k)||!p.tokens.includes(k)||!["last4","receipt","phone"].includes(v)))return false;
  return p.tokens.filter(t=>/^N/.test(t)).every(t=>Object.hasOwn(p.numberKinds,t));
}
export function validResult(r,p) {
  if(!r||typeof r!=="object"||Object.keys(r).some(k=>!["action","numbers","worker","askSms","need"].includes(k)))return false;
  if(!["lookup","finish","report","introduce","yes","no","cancel","clarify"].includes(r.action))return false;
  if(r.numbers!==undefined&&(!Array.isArray(r.numbers)||!r.numbers.every(n=>Object.hasOwn(p.numberKinds,n))))return false;
  if(r.worker!==undefined&&r.worker!==null&&!(r.worker==="current"&&p.hasCurrentWorker)&&!p.tokens.filter(t=>/^W/.test(t)).includes(r.worker))return false;
  if(r.askSms!==undefined&&typeof r.askSms!=="boolean")return false;
  if(r.need!==undefined&&!["worker","receipt","number","action"].includes(r.need))return false;
  return true;
}
const system=`Classify a tailor shop assistant request. Input contains ONLY allowlisted English or transliterated Russian operational words. N1 etc are opaque number references; W1 etc are opaque worker references. Never invent references. Unknown words are unavailable. Never guess a digit or worker.
Return one JSON object: {"action":"lookup|finish|report|introduce|yes|no|cancel|clarify","numbers":["N1"],"worker":"W1|current" or null,"askSms":true,"need":"worker|receipt|number|action"}.
lookup = customer pickup search or status inquiry. report = question about saved work, who did/recorded it, under whom or whether it is in Daily Report. These are read only, NEVER finish. finish = an affirmative request to record completed work, save a receipt, or send a ready message. A negated completion or a question about existing work is NEVER finish. introduce = worker introduction without an action. yes/no only in sms or pickup waiting state. If action or number is ambiguous, clarify. A finish requires a receipt-kind number, a lookup can use last4/phone/receipt. Use current worker only when hasCurrentWorker is true. AskSms false only if explicitly without/no SMS/just save; otherwise true. Do not treat "who did" as a worker introduction or recording. /no_think`;
const response=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{"content-type":"application/json","cache-control":"no-store"}});
export default {
 async fetch(request,env) {
  const url=new URL(request.url);
  if(request.method==="GET"&&url.pathname==="/health")return response({service:"sofia-intent",schema:1});
  if(request.method!=="POST"||url.pathname!=="/intent")return response({error:"not_found"},404);
  if(!env.INTENT_TOKEN||request.headers.get("authorization")!==`Bearer ${env.INTENT_TOKEN}`)return response({error:"unauthorized"},401);
  if(Number(request.headers.get("content-length")||0)>4096)return response({error:"too_large"},413);
  try {
   const raw=await request.text();if(raw.length>4096)return response({error:"too_large"},413);
   const p=JSON.parse(raw);if(!validPayload(p))return response({error:"schema"},400);
   const generated=await Promise.race([env.AI.run("@cf/qwen/qwen3-30b-a3b-fp8",{messages:[{role:"system",content:system},{role:"user",content:JSON.stringify(p)}],max_tokens:256,temperature:0,response_format:{type:"json_object"}}),new Promise((_,reject)=>setTimeout(()=>reject(Error("timeout")),4500))]);
   const value=generated?.choices?.[0]?.message?.content??generated?.response;
   const r=typeof value==="string"?JSON.parse(value):value;
   if(!validResult(r,p))return response({error:"invalid_suggestion"},502);
   return response(r);
  } catch(_) { return response({error:"unavailable"},503); }
 }
};
