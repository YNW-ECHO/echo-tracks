"""Mirror of Kotlin SmsReader + CategoryRules + MpesaParser. Must pass 100% before APK."""
import re
from pathlib import Path

def detect_source(sender, body=""):
    s, b = sender.upper(), body.upper()
    if "M-PESA" in s or "MPESA" in s: return "MPESA"
    if "M-PESA" in b and "CONFIRMED" in b: return "MPESA"
    if any(k in s for k in ["KCB","EQUITY","CO-OP","COOP","STANBIC","ABSA"]): return "BANK"
    if re.search(r"\b(KCB|EQUITY|CO-?OP|STANBIC|ABSA|BALANCE|ACCOUNT \d)\b", b) and "CONFIRMED" not in b: return "BANK"
    return "UNKNOWN"

CATMAP = [
    (["kplc","kplc token","electricity","nairobi water","dstv","gotv","startimes","nhif","rent","school fees"], "BILLS"),
    (["java house","kfc","pizza","restaurant","cafe","food","eats","chips","nyama","java"], "FOOD"),
    (["bolt","uber","little ride","matatu","nduthi","fare","ride","fuel","shell","totalenergies"], "FARE"),
    (["naivas","carrefour","quickmart","chandarana","supermarket","naivas deli"], "SHOPPING"),
    (["airtime","bundles","data bundles","safaricom data"], "AIRTIME"),
    (["atm","withdrawal of","withdrawal at","agent withdrawal","cash withdrawal"], "CASH"),
]
PERSON = re.compile(r"07\d{8}|01\d{8}|\+254\d{9}")

def categorize(who, raw):
    t = f"{who} {raw}".lower()
    for keys, cat in CATMAP:
        if any(k in t for k in keys): return cat
    # FIX: pochi/till/paybill before person (Mama Mboga Pochi was misclassified TRANSFER)
    if "till" in t or "paybill" in t or "pochi" in t: return "SHOPPING"
    if PERSON.search(who) and ("sent to" in t or "send money" in t): return "TRANSFER"
    return "UNCATEGORIZED"

def spend_type(raw, direction):
    t = raw.lower()
    if "fuliza" in t or "m-shwari" in t or "mshwari" in t or "overdraft" in t or ("loan" in t and ("disbursed" in t or "repayment" in t or "kcb m-pesa" in t)): return "LOAN"
    if "reversal" in t or "reversed" in t or "refund" in t: return "UNKNOWN"
    # FIX: M-PESA payments always mention "Transaction cost" — that doesn't make them fees.
    # Only standalone fee messages (no paid/sent/received/airtime) are FEE.
    is_payment = any(k in t for k in ["paid to","sent to","you have received","received ksh","received kes","airtime purchased","deposit of","debit of","withdrawal of","credit of"])
    if is_payment:
        return "INCOME" if direction == "IN" else "REAL_SPEND"
    if "transaction cost" in t or "transaction charge" in t: return "FEE"
    if re.search(r"\bfee\b|\bcharges?\b|\blevy\b", t) and ("deducted" in t or "charged" in t or "cost" in t): return "FEE"
    return "INCOME" if direction == "IN" else "REAL_SPEND"

def extract_who(body):
    m = re.search(r"paid to (.+?)\s+(till|paybill|pochi)[^\.]*\.?\s*on", body, re.I)
    if m: return m.group(1).strip()[:60]
    m = re.search(r"paid to (.+?)\.\s*on", body, re.I)
    if m: return m.group(1).strip()[:60]
    m = re.search(r"sent to (.+?)\s+on\s+\d", body, re.I)
    if m: return m.group(1).strip()[:60]
    m = re.search(r"(?:reversal of.+?from|you have received.+?from|received.+?from)\s*(.+?)\s+on\s+\d", body, re.I)
    if m: return m.group(1).strip()[:60]
    m = re.search(r"airtime purchased for (\d+)", body, re.I)
    if m: return f"Airtime {m.group(1)}"
    m = re.search(r"(?:debit of.+?for|purchase of.+?at|withdrawal of.+?at)\s*([A-Z][A-Z0-9 .&'\-]{2,40})", body, re.I)
    if m: return m.group(1).strip().rstrip(".")[:60]
    m = re.search(r"(?:at|from|to)\s+([A-Z][A-Z0-9 .&'\-]{3,40})\s+on\s+\d", body, re.I)
    if m: return m.group(1).strip()[:60]
    return "Unknown"

def parse(body):
    # FIX: allow amounts without decimals (Ksh450) — old regex only matched 450.00
    m = re.search(r"(?:Ksh|KES)\s?([\d,]+(?:\.\d{2})?)", body, re.I)
    if not m: return None
    amt = float(m.group(1).replace(",", ""))
    low = body.lower()
    is_in = any(k in low for k in ["you have received","received ksh","received kes","deposit of","credit of"]) or ("reversal" in low and "from" in low)
    who = extract_who(body)
    d = "OUT" if not is_in else "IN"
    if "withdrawal of" in low or "debit of" in low: d = "OUT"
    return amt, who, d, categorize(who, body), spend_type(body, d)

SENDERS = ["M-PESA","M-PESA","M-PESA","M-PESA","M-PESA","M-PESA","M-PESA","M-PESA","M-PESA","M-PESA",
           "KCB-ALERT","KCB-ALERT","EQUITY","EQUITY","ARTISAN"]
EXPECTED = [  # (direction, category, spendtype) per line
    ("OUT","SHOPPING","REAL_SPEND"), ("OUT","TRANSFER","REAL_SPEND"),
    ("IN","UNCATEGORIZED","INCOME"), ("OUT","FARE","REAL_SPEND"),
    ("OUT","AIRTIME","REAL_SPEND"), ("OUT","FOOD","REAL_SPEND"),
    ("OUT","BILLS","REAL_SPEND"), ("OUT","SHOPPING","REAL_SPEND"),
    ("OUT","UNCATEGORIZED","LOAN"), ("IN","UNCATEGORIZED","UNKNOWN"),
    ("OUT","CASH","REAL_SPEND"), ("OUT","UNCATEGORIZED","FEE"),
    ("IN","UNCATEGORIZED","INCOME"), ("OUT","BILLS","REAL_SPEND"),
    ("OUT","FOOD","REAL_SPEND"),  # COFFEE must NOT be FEE (old bug)
]

if __name__ == "__main__":
    lines = [l for l in Path(__file__).parent.joinpath("sample_sms.txt").read_text().splitlines() if l.strip()]
    assert len(lines) == len(EXPECTED) == len(SENDERS), f"counts {len(lines)}/{len(EXPECTED)}/{len(SENDERS)}"
    fails = 0
    spent = inc = 0.0
    for i, (line, exp, snd) in enumerate(zip(lines, EXPECTED, SENDERS)):
        src = detect_source(snd, line)
        assert src in ("MPESA","BANK"), f"line {i} source UNKNOWN: {line[:60]}"
        got = parse(line)
        assert got, f"line {i} unparsed: {line[:60]}"
        amt, who, d, cat, st = got
        ok = (d, cat, st) == exp
        if not ok: fails += 1
        if d == "OUT" and st in ("REAL_SPEND",): spent += amt
        if d == "IN" and st == "INCOME": inc += amt
        print(f"{'PASS' if ok else 'FAIL'} [{i:02}] {d}/{cat}/{st} expected {exp[0]}/{exp[1]}/{exp[2]} | {amt:.2f} {who}")
    print(f"\nClean spent (hide-noise ON): {spent:.2f} | income: {inc:.2f}")
    # hide-noise must exclude fuliza loan + reversal + kcb fee
    assert abs(spent - (2340+500+350+1500+850+1200+200+5000+3500+450)) < 0.01, f"spent wrong {spent}"
    assert abs(inc - (10000+20000)) < 0.01, f"income wrong {inc}"
    print("ALL TESTS PASS" if fails == 0 else f"{fails} FAILURES")
    raise SystemExit(1 if fails else 0)
