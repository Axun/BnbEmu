# -*- coding: utf-8 -*-
with open('cap/chan_s2c_op65_poshevie.bin', 'rb') as f:
    d = f.read()

body = d[2:] # strip 2B opcode
print('Body length:', len(body))

lines = []
for i in range(0, len(body), 24):
    chunk = body[i:i+24]
    dwords = [chunk[j:j+4].hex() for j in range(0, len(chunk), 4)]
    lines.append('    ' + ' '.join(dwords))

content = '''package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// 進頻道握手推送 #10 (seq=127, 50KB 資源塊 / 商城目錄, 含 Posh Evie 角色卡道具)
fun Unk65() = PKT {
    EncodeBuffer(EN_UNK65_BLOB)
}

private val EN_UNK65_BLOB = """
''' + '\n'.join(lines) + '\n""".toBA()\n'

with open('src/main/kotlin/pingu/packet/Unk65.kt', 'w', encoding='utf-8') as f:
    f.write(content)

print('Successfully wrote src/main/kotlin/pingu/packet/Unk65.kt')
