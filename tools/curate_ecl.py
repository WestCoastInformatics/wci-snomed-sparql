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

# Supplementary examples from the snomed-ecl-parser unit tests (develop branch: ECLQueryBuilderTest,
# ECLQueryBuilderWithFiltersTest) that add something beyond the spec examples
PARSER_TESTS = [
    ("Attribute groups joined by OR, any value",
     '< 404684003 |Clinical finding| : { 363698007 |Finding site| = * } OR { 116676008 |Associated morphology| = * }'),
    ("Refinement without whitespace",
     '<<404684003:363698007=<<123037004,116676008=<<415582006'),
    ("Two description filters (match/wild set, then match)",
     '< 64572001 |Disease| {{ term = (match:"gas" wild:"*itis") }} {{ term = "heart att" }}'),
    ("Term with a set of languages",
     '< 64572001 |Disease| {{ term = ("heart"), language = (en sv) }}'),
    ("Term, language and type",
     '< 64572001 |Disease| {{ term = "box", language = en, type = syn }}'),
    ("Conflicting typeId and type in one filter",
     '< 56265001 |Heart disease| {{ term = "heart", language = en, '
     'typeId = 900000000000013009 |Synonym|, type = fsn }}'),
    ("Dialect alias", '< 64572001 |Disease| {{ dialect = en-gb }}'),
    ("Dialect id", '< 64572001 |Disease| {{ dialectId = 900000000000508004 |Great Britain English language '
     'reference set| }}'),
    ("Dialect id and a dialect without a language refset in the edition",
     '< 64572001 |Disease| {{ dialectId = 900000000000508004 }} {{ dialect = en-ie }}'),
    ("Dialect not in a set", '< 64572001 |Disease| {{ term = "card", dialect != ( en-nhs-clinical en-nhs-pharmacy ) }}'),
    ("Dialects with different acceptabilities",
     '< 64572001 |Disease| {{ term = "box", type = syn, dialect = ( en-gb (accept) en-nhs-clinical (prefer) ) }}'),
    ("Dialect set with acceptability on one", '< 64572001 |Disease| {{ dialect = ( en-us en-gb (prefer) ) }}'),
    ("Negated type and dialect",
     '< 125605004 |Fracture of bone| {{ term = "fracture", type != syn, dialect != en-us (prefer) }}'),
    ("Description filter on a refinement value",
     '< 404684003 |Clinical finding| : 363698007 |Finding site| = << 39057004 |Pulmonary valve structure| , '
     '116676008 |Associated morphology| = << 415582006 |Stenosis| {{ term = "insufficiency" }}'),
    ("Description filter on a bracketed refined expression",
     '( < 404684003 |Clinical finding| : 363698007 |Finding site| = << 39057004 |Pulmonary valve structure| , '
     '116676008 |Associated morphology| = << 415582006 |Stenosis| ) {{ term = "insufficiency" }}'),
]
for k, (heading, ecl) in enumerate(PARSER_TESTS, 1):
    out.append({"id": f"P-{k:02d}", "section": "P snomed-ecl-parser tests", "heading": heading, "ecl": ecl,
                "eclLong": None, "executable": True, "note": None,
                "source": "snomed-ecl-parser develop: src/test/java/org/snomed/langauges/ecl/"})

# International edition adaptations of spec examples whose content is not in (or is inactive in) the International
# release: 700043003 |Example problem list concepts reference set| -> 447562003 |ICD-10 complex map reference set|,
# 445238008 |Malignant carcinoid tumor| (inactive) -> 55342001 |Neoplastic disease|, the AU dialect -> GB English,
# the US module -> the core module, Hay asthma (inactive) -> a SAME AS member
PROBLEM_LIST = "700043003 |Example problem list concepts reference set|"
ICD10 = "447562003 |ICD-10 complex map reference set|"
CARCINOID = "445238008 |Malignant carcinoid tumor|"
NEOPLASTIC = "55342001 |Neoplastic disease|"
ADAPTED = ["6.1-10", "6.4-05", "6.4-06", "6.4-07", "6.4-08", "6.4-10", "6.4-11", "6.4-15", "6.4-16", "6.4-17",
           "6.4-18", "6.4-19", "6.4-20", "6.5-02", "6.7-01", "6.7-08", "6.7-09"]
by_id = {e["id"]: e for e in out}
adapted = []
for spec_id in ADAPTED:
    e = by_id[spec_id]
    adapted.append((spec_id, e["heading"],
                    e["ecl"].replace(PROBLEM_LIST, ICD10).replace(CARCINOID, NEOPLASTIC)))
adapted.append(("6.8-18", by_id["6.8-18"]["heading"],
                "< 64572001 |Disease| {{ dialectId = 900000000000508004 |Great Britain English language reference "
                "set| }}"))
adapted.append(("6.9-06", by_id["6.9-06"]["heading"],
                "< 195967001 |Asthma| {{ C moduleId = 900000000000207008 |SNOMED CT core module| }}"))
adapted.append(("6.10-07", by_id["6.10-07"]["heading"],
                "^ 900000000000527005 |SAME AS association reference set| {{ M referencedComponentId = "
                "155574008 |Asthma| }}"))
adapted.append(("6.10-07", by_id["6.10-07"]["heading"],
                "^ 900000000000527005 |SAME AS association reference set| {{ M targetComponentId = "
                "<< 195967001 |Asthma| }}"))
for k, (spec_id, heading, ecl) in enumerate(adapted, 1):
    out.append({"id": f"A-{k:02d}", "section": "A International adaptations", "heading": heading, "ecl": ecl,
                "eclLong": None, "executable": True,
                "note": f"Adapted from {spec_id} to content in the International edition.",
                "source": f"Adapted from {spec_id}"})

for e in out:
    e["features"] = features(e["ecl"]) if e["ecl"] else []

keys = ["id", "section", "heading", "ecl", "eclLong", "features", "executable", "note", "source"]
print(json.dumps([{k: e[k] for k in keys} for e in out], indent=2, ensure_ascii=False))
