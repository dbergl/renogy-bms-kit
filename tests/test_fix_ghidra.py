import pytest

import fix_ghidra


def brset(form):
    return (f':BRSET {form}, msk8, rel8    is Prefix18=0 & op8=0x1E; {form}; msk8; rel8\n'
            f'{{\n\tresult:1 = ~{form} & msk8;\n\tif (result != 0) goto rel8;\n}}\n\n')


def branch(op, rel):
    return f':{op} {rel}                 is Prefix18=0 & op8=0x2C; {rel}\n{{\n\tif (($(N) ^ $(V)) == 1) goto {rel};\n}}\n\n'


def cpe(op, reg, form):
    return (f':{op} {form}                 is Prefix18=1 & (op8=0x8C); {form}\n{{ \n\top1:2 = {form};\n'
            f'\ttmp:2 = {reg} - (op1 + zext($(C)));\n\t$(Z) = (tmp == 0);\n\t$(N) = (tmp s< 0);\n'
            f'\t$(C) = (op1 > {reg});\n\tV_CMP_flag({reg}, op1);\n}}\n\n')


FORMS = ('iopr16i', 'opr8a_16', 'opr16a_16', 'indexed2_5')
STOCK = (''.join(brset(f) for f in ('opr8a_8', 'opr16a_8', 'indexed1_3'))
         + branch('BGE', 'rel8') + branch('BLT', 'rel8') + branch('LBGE', 'rel16')
         + ''.join(cpe(op, reg, f) for op, reg in (('CPED', 'D'), ('CPES', 'SP'), ('CPEX', 'IX'), ('CPEY', 'IY'))
                   for f in FORMS))


def test_brset_branches_when_all_bits_set():
    out = fix_ghidra.patch_sinc(STOCK)
    assert out.count('if (result == 0) goto rel8;') == 3
    assert 'if (result != 0)' not in out


def test_bge_fixed_and_blt_untouched():
    out = fix_ghidra.patch_sinc(STOCK)
    assert ':BGE rel8                 is Prefix18=0 & op8=0x2C; rel8\n{\n\tif (($(N) ^ $(V)) == 0) goto rel8;' in out
    assert ':LBGE rel16                 is Prefix18=0 & op8=0x2C; rel16\n{\n\tif (($(N) ^ $(V)) == 0) goto rel16;' in out
    assert ':BLT rel8                 is Prefix18=0 & op8=0x2C; rel8\n{\n\tif (($(N) ^ $(V)) == 1) goto rel8;' in out


def test_cpe_sticky_z_and_borrow():
    out = fix_ghidra.patch_sinc(STOCK)
    assert out.count('$(Z) = $(Z) & (tmp == 0);') == 16
    assert out.count('$(C) = sub > reg;') == 16
    assert 'reg:4 = zext(IX);' in out and '((IX ^ op1) & (IX ^ tmp)) s< 0' in out
    assert 'V_CMP_flag' not in out


def test_refuses_patched_or_unknown_text():
    with pytest.raises(fix_ghidra.PatchError):
        fix_ghidra.patch_sinc(fix_ghidra.patch_sinc(STOCK))
    with pytest.raises(fix_ghidra.PatchError):
        fix_ghidra.patch_sinc(STOCK.replace(brset('indexed1_3'), ''))


@pytest.mark.ghidra
def test_patches_the_real_language(ghidra_home):
    text = (ghidra_home / fix_ghidra.LANG / 'HCS_HC12.sinc').read_text()
    out = fix_ghidra.patch_sinc(text)
    assert out.count('$(Z) = $(Z) & (tmp == 0);') == 16
