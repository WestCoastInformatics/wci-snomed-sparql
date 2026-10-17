"""Curate raw extracted ECL spec examples into the test catalog."""
import json, re, sys

raw = json.load(open(sys.argv[1]))
out = []
for e in raw:
    if e["id"] == "6.8-02":
        # one code block holding four alternative expressions
        for k, line in enumerate(e["ecl"].split("\n"), 1):
            out.append(dict(e, id=f"6.8-02{'abcd'[k-1]}", ecl=line.strip(), eclLong=None))
        continue
    out.append(dict(e))

for e in out:
    e["executable"] = True
    e["note"] = None
    if e["id"] == "6.6-01":
        e["ecl"] = ("/* Disorders of lung with edema */\n"
                    "< 19829001 |Disorder of lung| : /* Descendants of disorder of lung */\n"
                    "  116676008 |Associated morphology| = << 79654002 |Edema|\n"
                    "  /* Where the associated morphology is edema or a subtype */")
        e["note"] = ("Corrected: the published spec shows '/* ... / ... / ... */' (lost asterisks), which would "
                     "be a single comment; restored the intended /* ... */ comments.")
    if e["id"] in ("6.8-37", "6.8-38", "6.9-12", "6.9-13"):
        e["ecl"] = e["ecl"].replace('"20200731" }}', '"20200731") }}')
        e["note"] = "Corrected: the published spec is missing the closing ')' of the effectiveTime set."
    if e["id"] == "6.3-18":
        e["ecl"] = e["ecl"].replace("[1..]", "[1..*]")
        e["note"] = ("Corrected: the published spec shows '[1..]' (lost asterisks); the text says this is one of "
                     "four equivalent forms of the default [1..*] cardinality.")
    if e["id"] == "6.4-09":
        e["executable"] = False
        e["note"] = "The spec presents this as NOT valid: AND and OR mixed without brackets."
    if e["id"] == "6.8-01":
        e["executable"] = False
        e["note"] = "Fragment showing a description filter on its own (no focus concept); not an expression."
    if e["id"] == "6.1-24":
        e["executable"] = False
        e["note"] = ("Skipped for now as a degenerate case: '^*' (members of any reference set) is too expensive to "
                     "evaluate on Snowstorm (timed out). To be revisited at the end (see PLAN.md).")
    if "[[+ecl" in (e["ecl"] or ""):
        e["executable"] = False
        e["note"] = "Template showing how HISTORY expands; not an executable expression."

def features(ecl):
    f = set()
    s = re.sub(r"\|[^|]*\|", "", ecl)            # drop terms
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)  # drop comments
    s = re.sub(r'"[^"]*"', '""', s)              # drop string contents
    # filters: tag, then remove so their contents don't trigger structural tags
    for m in re.finditer(r"\{\{\s*(\+\s*HISTORY|[DCM]\b)?", s):
        g = (m.group(1) or "D").replace(" ", "")
        f.add({"D": "descriptionFilter", "C": "conceptFilter", "M": "memberFilter"}.get(g, "history"))
    s = re.sub(r"\{\{.*?\}\}", " ", s, flags=re.S)
    if re.search(r"\^\s*\[", s): f.add("memberFieldSelection")
    s = re.sub(r"\^\s*\[[^\]]*\]", "^", s)
    if re.search(r"\[\s*\d+\s*\.\.", s): f.add("cardinality")
    s = re.sub(r"\[\s*\d+\s*\.\.\s*[\d*]*\s*\]", " ", s)
    if re.search(r"!!>|!!<", s): f.add("topBottom")
    if re.search(r"[A-Za-z]+#", s): f.add("alternateIdentifier")
    if re.search(r"\^R\b", s): f.add("refsetContaining")
    elif "^" in s: f.add("memberOf")
    if ":" in s: f.add("refinement")
    if "{" in s: f.add("attributeGroup")
    if re.search(r"(^|[\s:,{(])R\s", s): f.add("reverse")
    if re.search(r"[)\d]\s*\.\s*[<>(\d]", s): f.add("dotted")
    if re.search(r"(?i)\b(AND|OR)\b|,", s): f.add("conjunctionDisjunction")
    if re.search(r"(?i)\bMINUS\b", s): f.add("minus")
    if "!=" in s: f.add("notEquals")
    if re.search(r"(?<![A-Za-z])#\d|=\s*\"\"|=\s*(TRUE|FALSE)\b", s): f.add("concreteValue")
    if re.search(r"<<!|<!|>>!|>!", s): f.add("childParent")
    if re.search(r"(<<|>>|(?<![!<>=])[<>](?![=!]))", s.replace("!!<", "").replace("!!>", "")): f.add("hierarchy")
    if "*" in s: f.add("wildcard")
    if "/*" in ecl: f.add("comment")
    return sorted(f)

# Supplementary examples: from the "ECL as SPARQL" draft document, not in the current behaviour pages
out.append({
    "id": "S-01", "section": "S Supplementary", "heading": "Precoordinated focus concept with refinement",
    "ecl": "19829001 |Disorder of lung| : 116676008 |Associated morphology| = 40829002 |Acute edema|",
    "eclLong": None, "executable": True,
    "note": "From the 'ECL as SPARQL' draft document (Refinements #3); not in the current spec behaviour pages.",
    "source": "ECL as SPARQL.docx"})

for e in out:
    e["features"] = features(e["ecl"]) if e["ecl"] else []

keys = ["id", "section", "heading", "ecl", "eclLong", "features", "executable", "note", "source"]
print(json.dumps([{k: e[k] for k in keys} for e in out], indent=2, ensure_ascii=False))
