import sys
import re

def fix_u_sequences(data):
    """Replace literal backslash-u sequences with actual Unicode characters."""
    return re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), data)

# Fix values/strings.xml
with open(r'D:\clawtemp\hermes-relay-cn\app\src\main\res\values\strings.xml', 'r', encoding='utf-8') as f:
    en_content = f.read()

count_en = len(re.findall(r'\\u[0-9a-fA-F]{4}', en_content))
print(f"Found {count_en} backslash-u sequences in values/strings.xml")

en_fixed = fix_u_sequences(en_content)

with open(r'D:\clawtemp\hermes-relay-cn\app\src\main\res\values\strings.xml', 'w', encoding='utf-8') as f:
    f.write(en_fixed)

# Fix values-zh/strings.xml
with open(r'D:\clawtemp\hermes-relay-cn\app\src\main\res\values-zh\strings.xml', 'r', encoding='utf-8') as f:
    zh_content = f.read()

count_zh = len(re.findall(r'\\u[0-9a-fA-F]{4}', zh_content))
print(f"Found {count_zh} backslash-u sequences in values-zh/strings.xml")

zh_fixed = fix_u_sequences(zh_content)

with open(r'D:\clawtemp\hermes-relay-cn\app\src\main\res\values-zh\strings.xml', 'w', encoding='utf-8') as f:
    f.write(zh_fixed)

print("Done fixing XML unicode escape sequences")