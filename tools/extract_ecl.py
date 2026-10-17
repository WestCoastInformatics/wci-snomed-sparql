"""Extract ECL examples from the spec's 'Behaviour specification with examples' markdown pages."""
import json, re, sys, os, html

BASE = "https://docs.snomed.org/snomed-ct-specifications/snomed-ct-expression-constraint-language/behaviour-specification-with-examples/"

def extract(path):
    name = os.path.basename(path)[:-3]
    num = name.split("-")[0]
    text = open(path, encoding="utf-8").read()
    lines = text.split("\n")
    title = next(l[2:].strip() for l in lines if l.startswith("# "))
    examples, heading, subheading = [], None, None
    tab, group, in_tabs = None, None, False
    i = 0
    while i < len(lines):
        l = lines[i]
        m = re.match(r"^(#{2,4}) (.*)", l)
        if m:
            h = re.sub(r"<img[^>]*>", "", m.group(2)).strip()
            if len(m.group(1)) == 2:
                heading, subheading = h, None
            else:
                subheading = h
        elif l.startswith("{% tabs %}"):
            in_tabs, group = True, {}
        elif l.startswith("{% endtabs %}"):
            if group:
                examples.append((heading, subheading, group))
            in_tabs, group = False, None
        elif m2 := re.match(r'\{% tab title="([^"]*)" %\}', l):
            tab = m2.group(1)
        elif l.startswith("```") or l.startswith("<pre"):
            if l.startswith("```"):
                j = i + 1
                body = []
                while not lines[j].startswith("```"):
                    body.append(lines[j]); j += 1
                code = "\n".join(body).strip()
            else:
                j = i
                body = [l]
                while "</pre>" not in lines[j]:
                    j += 1; body.append(lines[j])
                code = html.unescape(re.sub(r"<[^>]+>", "", "\n".join(body))).strip()
            i = j
            if code.startswith("GET https://docs.snomed.org"):
                i += 1
                continue
            if in_tabs:
                key = "long" if "long" in tab.lower() else "brief"
                group.setdefault(key, []).append(code)
            else:
                examples.append((heading, subheading, {"brief": [code]}))
        i += 1
    out = []
    for n, (h, sh, g) in enumerate(examples, 1):
        brief = g.get("brief", [])
        long = g.get("long", [])
        # tabs with several code blocks per tab: pair them up positionally
        for k in range(max(len(brief), len(long))):
            out.append({
                "section": f"{num} {title}",
                "heading": h if not sh else f"{h} / {sh}",
                "ecl": brief[k] if k < len(brief) else None,
                "eclLong": long[k] if k < len(long) else None,
                "source": BASE + name,
            })
    for n, e in enumerate(out, 1):
        e["id"] = f"{num}-{n:02d}"
    return out

def key(p):
    return [int(x) for x in os.path.basename(p).split("-")[0].split(".")]

allx = []
for p in sorted(sys.argv[1:], key=key):
    allx.extend(extract(p))
print(json.dumps(allx, indent=2, ensure_ascii=False))
