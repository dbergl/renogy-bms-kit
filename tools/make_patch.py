#!/usr/bin/env python3
"""Build the in-place additive manual-heater patch from exact stock V137.4.5.

Commands arrive on CAN1 as RV-C clause 6.7 proprietary DGN 0x18EF7000 | SA,
DLC=8, data AA 55 00 00 00 00 HH LL. HH:LL is a 100 ms lease; zero cancels only
manual heating. Invalid frames do nothing. The PIT interrupt owns expiry; CAN
and PIT serialize with I=1. Manual leases cancel when the minimum probe reaches
charge L1 + 5 C. Stock automatic heating remains additive and retains its own
control logic.

The same ID with data 4D 53 00 00 00 00 HH LL leases the CAN1 response masks
for HH:LL 100 ms ticks; zero disables. Reset disables masking.

Both are admitted only in system state 3 or 4, the condition the stock CAN4
dispatcher applied at 7f4e39. Paying for that gate is why CAN4 no longer
carries commands: its 0x999 case is inert, which frees the seven bytes the
state compare needs in a 77-byte region that had one to spare.

CAN1 busy release: stock leaves a 4-word (or multiple-of-4) request busy until
the next main-loop pass after its final frame, silently dropping a request that
arrives in between. The 4-word tail now branches on the decremented count
itself: zero enters stock's count==0 completion (clears busy with the final
frame, as stock already does for 1-3 words); nonzero returns unconditionally.
It never reads the PIT-owned pacing flag, so a pacing interrupt cannot route a
>4-word remainder into the partial-copy path.

Status broadcast: RV-C proprietary B 0x18FF7070 on CAN1 every 20th 100 ms
tick, and once more after every accepted command, built and sent from the main
loop (docs/can-protocol.md). It lives in 168 added bytes at
0x5738-0x57DF, six 32-byte records inserted directly after the last stock S1
record, inside the 1 KB erase sector that record already occupies.

--cvl additionally makes the charge voltage limit (register 0x138F) and its
54.6 V "full" threshold follow the IR3 setting: three 3-byte splices and one
32-byte record at FE:BFE0 (docs/charge-limits.md). Without it the
output is the reviewed status-broadcast candidate, unchanged.

--slew additionally limits how fast the published charge current limit
(register 0x1390) may fall, so the APS-500 never sees a cut large enough to
zero its field: one 3-byte splice and one 32-byte record at 0x57E0
(docs/charge-limits.md). --slew is 1/128 per pass; --slew256 is 1/256 per
pass, the rate the published recipes use.

--uvcharge (needs --slew or --slew256) lets the alternator charge a low pack
and stops it before the BMS opens PA1 (docs/charge-limits.md). The APS-500
faults on the undervoltage bits in registers 1393/1394, so they are hidden
until a PA1 cut is near: 290 s into the charge-mode L3 hold, 6 s into the
10 s L3 countdown when not charging, or, while charging, as soon as the BMS
starts counting net discharge toward leaving charge mode with that countdown
past 6 s (the exit opens PA1 at once, 3 s after the count starts). Then the
bits are shown again and 0x1390 goes to zero, so the regulator drops its
field first. Cell UV L2 caps the request at 42 A and L3 at 21 A. While either
L3 countdown runs, the CAN4 0x100 message's work-mode byte is ignored, so no
command can end charge mode ahead of that warning; its balancing fields still
apply. Three splices and four 32-byte records at FC:B780, the Tier 1 tail of
page FC.

Eleven existing ranges are replaced and six records are added. Compact CAN ID
decoding and CAN1 copying free space for the additive helpers.
No bootloader is exercised.
"""
from pathlib import Path
import hashlib
import sys

STOCK_SHA256 = 'c589c76ae339e9a869f2e27b79ce2e0016b9eb38b469f8cf91ed74d838ee1932'
DECODE_BASE = 0x4D78
DECODE_END = 0x4E39
RECOVERY = 0x55BA  # unused tail of replaced CAN receiver; unpaged callable helper
MASK_TICK = 0xAD15  # remaining space in the replaced page-FE full-copy region
BUSY_TAIL = 0xFEAD5F  # 4-word CAN1 send tail, after the index update
BUSY_RETURN = 0xADB2  # spare padding in the replaced page-FE partial-copy region
# Stock: LDX 26E4; LEAX -4,X; STX 26E4; CLR 26E1; JMP AE16 (busy stays set).
BUSY_STOCK = bytes.fromhex('FE26E4 1A1C 7E26E4 7926E1 06AE16')
CAN1_WRAPPER = 0x9158  # padding tail of the replaced page-FE heater output region
CAN1_SPLICE = 0x4CD4   # ISR_CAN1_rx: the busy test, after the 18EF7000 ID match
CAN1_EXIT = 0x4D3A     # ISR_CAN1_rx: clear RXF, CLI, RTI
# Stock: LDAB 26DF; CMPB #0; BNE 4D3A. Replaced by a paged call to the shared
# command dispatcher, which returns the same Z convention: set means "decode a
# register request", clear means "leave the frame alone".
CAN1_SPLICE_STOCK = bytes.fromhex('F626DF C100 265F')
# Status broadcast: new code in the Tier 1 unpaged window past the last stock
# byte (0x5736). 0x5738 is 8-byte phrase aligned, so no phrase is programmed
# twice. See docs/can-protocol.md.
STATUS = 0x5738
STATUS_END = 0x5800
# Settable charge voltage limit (--cvl, docs/charge-limits.md).
# IR3, a setting nothing reads, carries the limit in 0.1 V. cvl_get clamps it
# and falls back to stock; cvl_cmp replaces CPD #0x222 (54.6 V = limit - 0.1 V)
# at its two sites. 32 bytes fill the Tier 1 tail of page FE exactly.
CVL = 0xFEBFE0
CVL_END = 0xFEC000
CVL_IR3 = 0x2674
CVL_MIN, CVL_MAX, CVL_STOCK = 520, 560, 547
# --cvl570 raises the upper bound to 57.0 V: one
# operand byte, CPD #0x230 -> #0x23A at FE:BFE8. Note 57.0 V is above the stock
# pack over-voltage L1 (tvol_h1, 56.8 V).
CVL_MAX_570 = 570
CVL_SPLICES = [(0xFEA8A4, bytes.fromhex('CC0223'), 'cvl_get'),   # LDD #547 before STD 23BC
               (0xFE9537, bytes.fromhex('8C0222'), 'cvl_cmp'),   # soc_calc charger-full rule
               (0xFEA974, bytes.fromhex('8C0222'), 'cvl_cmp')]   # register 0x1391
# Charge current slew limit (--slew, docs/charge-limits.md). The
# APS-500 zeroes its field when measured current reaches ~1.18x the limit it
# was given, so the published limit (register 0x1390, RAM 0x23BE) may fall by
# at most 1/128 of itself per 100 ms task pass (~7.5 %/s). Zero (alarm or
# temperature stop) and any increase pass at once; the fall starts from the
# regulator's own 90 A clamp. The 32 bytes left in the Tier 1 window after the
# status broadcast hold it; one 3-byte splice calls it.
SLEW = 0x57E0
SLEW_SPLICE = 0xFEA96E      # task_soc_or_balance: STD 0x23BE after LDD 2,SP
SLEW_SPLICE_STOCK = bytes.fromhex('7C23BE')
SLEW_FLOOR = -9000          # -90.00 A, the APS-500's hard-coded clamp
# Undervoltage charging (--uvcharge, docs/charge-limits.md). 128 bytes at
# FC:B780-B7FF, the Tier 1 tail of page FC after the last stock byte FC:B77C.
UV = 0xFCB780
UV_END = 0xFCB800
UV_CAP_SPLICE = 0xFEA96C    # the ladder's join: LDD 2,SP / JSR slew (after --slew)
UV_COPY_SPLICE = 0xFEAC0A   # register build: LDD 272A / STD 23C4 / LDD 272C / STD 23C6
UV_COPY_STOCK = bytes.fromhex('FC272A 7C23C4 FC272C 7C23C6')
UV_COPY_TAIL = bytes.fromhex('E603 A402 7C23C6')   # LDAB 3,X / ANDA 2,X / STD 23C6: 1394, after the CALL
UV_MODE_SPLICE = 0x500C     # CAN4 0x100 handler (unpaged): STD 0x2BE6 / LDAB 0x28,IX
UV_MODE_STOCK = bytes.fromhex('7C2BE6 E6E028')
UV_L2_CAP = -4200           # cell UV level 2: at most 42.00 A
UV_L3_CAP = -2100           # cell UV level 3: at most 21.00 A
UV_HOLD_WARN = 2900         # charge mode: alarm_post_adjust holds PA1 for 3000 ticks
UV_COUNT_WARN = 60          # otherwise: the L3 evals open PA1 past 99 ticks
UV_DCH_WARN = 1             # charge mode: mode_detect_chg_dch counts 0x25AF to 30
                            # over net discharge, then sets work mode 3, which ends
                            # the hold; the countdown rule applies from the first tick
UV_HIDE = 0xFA              # 1393/1394 high byte: pack UV L2 (04), cell UV (01)
# The image may grow, but never past the largest unpatched firmware Renogy has
# released for this battery: 137.4.1, a 119,202-byte S19 holding 48,988 programmed
# bytes (docs/firmware-map.md). 137.4.4, at 37,815,
# was the earlier, stricter cap. Flash above the stock sectors is still Tier 2.
MAX_IMAGE_BYTES = 48988
# Two main-loop splices. In each the displaced CALL becomes the first
# instruction of the new routine, so stock ordering is unchanged.
FAST_SPLICE = 0xFE8008   # main: the CAN1 transmitter call, every loop pass
TICK_SPLICE = 0xFE8073   # main: the last call of the 100 ms task block
FAST_SPLICE_STOCK = bytes.fromhex('4AAC93FE')   # CALL 0xAC93,0xFE
TICK_SPLICE_STOCK = bytes.fromhex('4AA333FE')   # CALL 0xA333,0xFE
# Fixed: CLR 26E1; LDX 26E4; LEAX -4,X; STX 26E4 (Z = count now zero);
# BNE busy_return (JMP AE16); NOP; fall into stock AD6D, whose LDD/CPD/LBEQ
# sends zero to AE10 (CLR 26E1; CLR 26DF). LEAX leaves CCR alone.
BUSY_FIXED = bytes.fromhex('7926E1 FE26E4 1A1C 7E26E4 26' + f'{BUSY_RETURN - 0xAD6C:02X}' + 'A7')


class Assembler:
    """Only fix up branch/JSR labels; explicit HCS12X opcodes remain reviewable."""
    def __init__(self, base):
        self.base, self.data, self.labels, self.fixups = base, bytearray(), {}, []

    def label(self, name):
        assert name not in self.labels
        self.labels[name] = self.base + len(self.data)

    def emit(self, hex_bytes):
        self.data.extend(bytes.fromhex(hex_bytes))

    def branch(self, opcode, label):
        self.emit(opcode)
        self.fixups.append((len(self.data), label, 1))
        self.data.append(0)

    def jsr(self, label):
        self.emit('16')
        self.fixups.append((len(self.data), label, 2))
        self.data.extend(b'\0\0')

    def finish(self, external=None):
        labels = dict(external or {}, **self.labels)
        for offset, label, width in self.fixups:
            value = labels[label]
            if width == 1:
                value -= self.base + offset + 1
                assert -128 <= value <= 127, (label, value)
                value &= 255
            self.data[offset:offset+width] = value.to_bytes(width, 'big')
        return bytes(self.data)


def decoder():
    a = Assembler(DECODE_BASE)
    # Preserve the original four byte-wide reads of the latched MSCAN ID.
    a.emit('FE261E E6E020 6B84 E6E021 6B85 E6E022 6B86 E6E023 6B87')
    a.emit('E685 C408')
    a.branch('27', 'standard')
    # high16 = IDR0<<5 | (IDR1&E0)>>3 | (IDR1&6)>>1
    a.emit('EC84 C4E0 49 49 49 6C80')
    a.emit('E685 C406 54 EA81 6B81')
    # low16 = (IDR1&1)<<15 | IDR2<<7 | IDR3>>1
    a.emit('EC86 49 6C82')
    a.branch('0F8501', 'decoded')  # BRCLR 5,SP,#1
    a.emit('0C8280')
    a.label('decoded')
    a.emit('064E39')              # skip helpers, join unchanged dispatcher
    a.label('standard')
    a.emit('EC84 49 49 49 49 49 6C82 186980 064E39')
    return a.finish()


def routines(base):
    a = Assembler(base)
    a.label('tick')
    a.emit('3B')                # PSHD: preserve interrupted scheduler's D
    a.emit(f'4A{MASK_TICK:04X}FE')  # replay task flag and tick independent mask lease
    a.emit('FC2250 8CAA55')
    a.branch('26', 'tick_done')
    a.emit('FC2254')
    a.branch('27', 'tick_apply')
    a.emit('830001 7C2254')     # SUBD #1 / STD lease; PIT ISR has I=1
    a.label('tick_apply')
    a.jsr('recovery')
    a.branch('2E', 'tick_cold') # threshold > minimum: retain manual lease
    a.emit('18792254')         # at/above recovery: cancel manual lease
    a.label('tick_cold')
    a.emit('4A95AEFC')          # stock overtemp debounce, exactly once per tick
    a.jsr('apply')
    a.emit('FC2254')
    a.branch('26', 'tick_done')
    a.emit('18792250')          # clear session magic after expiry reconciliation
    a.label('tick_done')
    a.emit('3A 3D')             # PULD / RTS

    a.label('apply')            # caller must have I=1 (ISR or output)
    a.emit('F62256')
    a.branch('26', 'off')
    a.emit('F62350 C401')       # stock request wins; never modify bit 0
    a.branch('26', 'on')
    a.emit('FC2250 8CAA55')
    a.branch('26', 'off')
    a.emit('FC2254')
    a.branch('27', 'off')
    a.emit('FC2BEC 8C0003')     # manual ON only in running system state
    a.branch('26', 'off')
    a.label('on')
    a.emit('1C235004')
    a.branch('20', 'apply_write')
    a.label('off')
    a.emit('1D235004')
    a.label('apply_write')
    a.jsr('write')
    a.emit('3D')

    a.label('write')
    a.emit('39 1410')           # atomic read of bit 2 + write PA3, including main
    a.branch('1F235004', 'write_off')
    a.emit('4C0008')            # BSET PORTA,8; preserve all other relay pins
    a.branch('20', 'write_done')
    a.label('write_off')
    a.emit('4D0008')
    a.label('write_done')
    a.emit('38 3D')
    labels = dict(a.labels, recovery=RECOVERY)
    return a.finish(labels), labels


def output(labels):
    a = Assembler(0x9139)
    a.emit('39 1410')           # PSHC / SEI: decision through physical write atomic
    a.emit('FC2250 8CAA55')
    a.branch('26', 'output_stock')
    a.jsr('recovery')
    a.branch('2E', 'output_apply')
    a.emit('18792254')         # reject/terminate a warm manual lease immediately
    a.branch('20', 'output_apply')
    a.label('output_stock')
    a.emit('4A95AEFC')          # no manual session: stock overtemp evaluation
    a.label('output_apply')
    a.jsr('apply')
    a.emit('38 0A')             # restore CCR / RTC (paged entry)

    # The padding tail of this replaced region holds the CAN1 command hook:
    # the shared dispatcher, then the stock busy test for anything that was not
    # a command. RTC leaves CCR alone, so the ISR's BNE sees these flags.
    assert a.base + len(a.data) <= CAN1_WRAPPER
    a.emit('A7' * (CAN1_WRAPPER - a.base - len(a.data)))
    a.label('can1_command')
    a.emit('FE2622')            # LDX CAN1 register pointer
    a.jsr('sub')
    a.branch('26', 'can1_return')
    a.emit('F626DF')            # LDAB 26DF: Z set only when the receiver is idle
    a.label('can1_return')
    a.emit('0A')
    result = a.finish(labels)
    assert len(result) == 43    # 9139..9163; 9164 is relay_output_update
    return result


def status_tx():
    """~2 s heater status broadcast on CAN1, plus an immediate frame whenever a
    command is accepted. Everything here is new flash in the Tier 1 window; the
    only in-place cost is two 4-byte main-loop splices and a size-for-size JMP
    in the dispatcher. INC/BSET/BCLR are single instructions, so the shared
    byte 0x2253 needs no critical section against ISR_CAN1_rx."""
    a = Assembler(STATUS)

    # Target of the dispatcher's JMP: a command was accepted, ask for a frame.
    a.label('ack_stub')
    a.emit('1C225340')              # BSET 0x2253,#0x40
    a.emit('C601 3D')               # LDAB #1: the caller's "was a command" flag

    # FE:8073, once per 100 ms task: the unconditional ~2 s heartbeat.
    a.label('periodic')
    a.emit('4AA333FE')              # displaced stock call, kept first
    a.emit('722253')                # INC 0x2253: divider in bits 0-5
    a.emit('F62253 C43F C114')      # LDAB / ANDB #3F / CMPB #20
    a.branch('25', 'p_ret')         # BLO: not due yet
    a.emit('1D22533F')              # BCLR 0x2253,#0x3F: reset count, keep bit 6
    a.jsr('send')
    a.label('p_ret')
    a.emit('3D')

    # FE:8008, every main-loop pass: service an accepted command.
    a.label('fast')
    a.emit('4AAC93FE')              # displaced stock CAN1 transmitter
    a.branch('1F225340', 'f_ret')   # BRCLR 0x2253,#0x40: nothing pending
    a.emit('1D225340')              # claim it before the payload is sampled
    a.jsr('send')
    a.emit('C100')                  # CMPB #0: B = can_tx result
    a.branch('26', 'f_ret')         # BNE: sent
    a.emit('1C225340')              # no transmit buffer: re-arm and retry
    a.label('f_ret')
    a.emit('3D')

    # Shared frame builder. Returns can_tx's result in B; LEAS touches neither.
    a.label('send')
    a.emit('1B98')                  # LEAS -8,SP: the payload
    a.emit('C670 6B80')             # b0 = 0x70, the proprietary-B tag
    a.emit('F62350 6B81')           # b1 = heater relay object
    a.emit('F62416 6B83')           # b3 = charge low-temperature alarm object
    a.emit('6982')                  # b2 = 0, flags built below
    # PSHC lowers SP by one, so no n,SP operand may appear between it and PULC:
    # the magic and the lease are sampled into registers, then stored outside.
    a.emit('39 1410')               # PSHC / SEI: magic and lease as one sample
    a.emit('FC2250 8CAA55')         # manual session armed?
    a.branch('26', 'no_lease')
    a.emit('FC2254')                # LDD lease while still serialized
    a.emit('38')                    # PULC
    a.emit('B781 6C84')             # b4:b5 = lease, little-endian, 0.1 s units
    a.emit('0C8201')                # b2 bit 0
    a.branch('20', 'lease_done')
    a.label('no_lease')
    a.emit('38')                    # PULC
    a.emit('6984 6985')             # no session: no lease
    a.label('lease_done')
    a.emit('B62252 81A5')           # alarm mask enabled?
    a.branch('26', 'no_mask')
    a.emit('0C8202')                # b2 bit 1
    a.label('no_mask')
    a.emit('F62256')                # g_heater_overtemp_flag
    a.branch('27', 'no_ot')
    a.emit('0C8204')                # b2 bit 2
    a.label('no_ot')
    a.emit('FC2BEC 8C0003')         # running system state?
    a.branch('26', 'flags_done')
    a.emit('0C8208')                # b2 bit 3
    a.label('flags_done')
    a.emit('FC233C B781 6C86')      # b6:b7 = min cell temp, little-endian
    # can_tx(8, payload, 0x18FF, 0x7070, CAN1 base): stock's own call sequence.
    a.emit('FC2622 6CAE')           # CAN1 register base
    a.emit('CE7070 6EAE')           # ID low 16
    a.emit('CD18FF 6DAE')           # ID high 16 -> 0x18FF7070, DGN 0FF70
    a.emit('1A86 6EAE')             # payload pointer, six bytes up after three pushes
    a.emit('C608 4A8B95FE')         # LDAB #8 / CALL can_tx
    a.emit('1BF010')                # LEAS 16,SP: arguments and payload
    a.emit('3D')
    return a.finish(), a.labels


def cvl(cvl_max=CVL_MAX):
    a = Assembler(CVL & 0xFFFF)
    # D = IR3 if CVL_MIN <= IR3 <= cvl_max, else 547 (54.7 V). Only D and CCR change.
    a.label('cvl_get')
    a.emit(f'FC{CVL_IR3:04X} 8C{CVL_MIN:04X}')
    a.branch('25', 'cvl_stock')     # BLO
    a.emit(f'8C{cvl_max:04X}')
    a.branch('23', 'cvl_ret')       # BLS
    a.label('cvl_stock')
    a.emit(f'CC{CVL_STOCK:04X}')
    a.label('cvl_ret')
    a.emit('3D')
    # Drop-in for CPD #(limit - 1): D unchanged, CCR as that CPD would leave it.
    # LEAS and RTS do not touch the CCR.
    a.label('cvl_cmp')
    a.emit('3B')                    # PSHD: the value under test
    a.jsr('cvl_get')
    a.emit('830001 3B')             # SUBD #1 / PSHD: threshold
    a.emit('EC82 AC80')             # LDD 2,SP / CPD 0,SP
    a.emit('1B84 3D')               # LEAS 4,SP / RTS
    code = a.finish()
    assert len(code) == CVL_END - CVL, 'CVL routines must fill the page FE tail exactly'
    return code, a.labels


def slew(rate=128):
    a = Assembler(SLEW)
    # Entry by JSR from FE:A96E with D = the selected limit T (signed 0.01 A,
    # charging negative) and CCR from the caller's LDD 2,SP; T is also at 4,SP.
    # Publishes min(T, max(O + step, -90 A)) where O is the previous value and
    # step = -(O asr 7) or -(O asr 8): |O|/rate rounded up for a negative O.
    # Changes D, CCR. rate 256 halves the fall rate of rate 128.
    assert rate in (128, 256)
    a.label('slew')
    a.branch('27', 'slew_store')    # BEQ: T == 0 stops charging at once
    if rate == 128:
        a.emit('FC23BE')            # LDD 0x23BE: O
        a.emit('59 40')             # ASLD / NEGA: A = -(O asr 7), exact for -16256 <= O < 16384
    else:
        a.emit('B623BE')            # LDAA 0x23BE: A = O asr 8, the high byte
        a.emit('40')                # NEGA: exact for -32512 <= O < 32768
    a.emit('B704')                  # SEX A,D: D = step
    a.emit('F323BE')                # ADDD 0x23BE: F = O + step
    a.emit(f'8C{SLEW_FLOOR & 0xFFFF:04X}')
    a.branch('2C', 'slew_floor_ok') # BGE: F at or above -90 A
    a.emit(f'CC{SLEW_FLOOR & 0xFFFF:04X}')
    a.label('slew_floor_ok')
    a.emit('AC84')                  # CPD 4,SP: F against T
    a.branch('2D', 'slew_store')    # BLT: F is more current than T, keep F
    a.emit('EC84')                  # LDD 4,SP: T
    a.label('slew_store')
    a.emit('7C23BE 3D')             # STD 0x23BE / RTS
    code = a.finish()
    assert len(code) <= 32
    return code.ljust(32, b'\xA7'), a.labels


def uvcharge(external):
    a = Assembler(UV & 0xFFFF)
    # B = 0xFF when the BMS may open PA1 soon for undervoltage L3, else UV_HIDE.
    # Charge mode (work mode 1): the 0x2502 hold has run UV_HOLD_WARN ticks, or
    # the discharge debounce 0x25AF (mode_detect_chg_dch, 7F:2C83) has run
    # UV_DCH_WARN ticks and the countdown rule below says so. Leaving charge
    # mode ends the hold in the same pass, before this code runs again.
    # Otherwise: either L3 countdown, 0x24DE (cell) or 0x24F2 (pack), has run
    # UV_COUNT_WARN ticks. Both counters read zero unless L3 is active.
    # Changes D, X and CCR.
    a.label('uv_mask')
    a.emit('C6FF')                  # LDAB #FF: show everything
    a.emit('FE2BE6 09')             # LDX work mode / DEX: Z iff charge mode
    a.branch('26', 'uv_idle')
    a.emit(f'FE2502 8E{UV_HOLD_WARN:04X}')
    a.branch('24', 'uv_soon')       # BHS
    a.emit(f'B625AF 81{UV_DCH_WARN:02X}')   # LDAA 25AF / CMPA #n
    a.branch('25', 'uv_hide')       # BLO: no net discharge being counted
    a.label('uv_idle')
    a.emit(f'FE24DE 8E{UV_COUNT_WARN:04X}')
    a.branch('24', 'uv_soon')
    a.emit(f'FE24F2 8E{UV_COUNT_WARN:04X}')
    a.branch('24', 'uv_soon')
    a.label('uv_hide')
    a.emit(f'C6{UV_HIDE:02X}')
    a.label('uv_soon')
    a.emit('3D')

    # CALLed from FE:AC0A in place of stock's 1393/1394 copy. Copies 1393 with
    # its high byte ANDed by the mask and returns A = mask, X = 272A; the
    # splice's remaining bytes (UV_COPY_TAIL) copy 1394 the same way. Changes
    # D, X, CCR; stock reloads both before using them.
    a.label('uv_copy')
    a.jsr('uv_mask')
    a.emit('37')                    # PSHB: the mask
    a.emit('B710')                  # TFR B,A
    a.emit('CE272A')                # LDX #272A
    a.emit('A400 E601')             # ANDA 0,X / LDAB 1,X: 1393 with its high byte ANDed
    a.emit('7C23C4')                # STD 23C4
    a.emit('32 0A')                 # PULA / RTC

    # CALLed from FE:A96C, the ladder's join, in place of LDD 2,SP / JSR slew.
    # T (the caller's 2,SP) is at 5,SP under the CALL frame. Publishes
    # slew(0) if a cut may be near, else slew(T capped by the cell UV level).
    # The caller's local is left alone. Changes D, X, CCR.
    a.label('uv_cap')
    a.jsr('uv_mask')
    a.emit('52')                    # INCB: Z iff B was 0xFF
    a.branch('27', 'uv_zero')
    a.emit('EC85')                  # LDD 5,SP: T; zero passes both caps unchanged
    a.branch('1F241780', 'uv_out')  # BRCLR cellUV,#80: level below 2
    a.emit(f'8C{UV_L2_CAP & 0xFFFF:04X}')
    a.branch('2C', 'uv_l3')         # BGE: T already at most the L2 cap
    a.emit(f'CC{UV_L2_CAP & 0xFFFF:04X}')
    a.label('uv_l3')
    a.branch('1F241740', 'uv_out')  # BRCLR cellUV,#40: level 2, not 3
    a.emit(f'8C{UV_L3_CAP & 0xFFFF:04X}')
    a.branch('2C', 'uv_out')
    a.emit(f'CC{UV_L3_CAP & 0xFFFF:04X}')
    a.branch('20', 'uv_out')
    a.label('uv_zero')
    a.emit('87 C7')                 # CLRA / CLRB
    a.label('uv_out')
    a.emit('3B 3B')                 # PSHD twice: the value is at 4,SP inside slew,
    a.emit('8C0000')                # where its JSR from A96E saw T; CPD #0 gives Z
    a.jsr('slew')
    a.emit('1B84 0A')               # LEAS 4,SP / RTC

    # CALLed from 500C in the CAN4 0x100 handler (ISR context) in place of
    # STD 0x2BE6 / LDAB 0x28,IX, with D = the frame's work-mode byte. Stores it
    # as stock unless an undervoltage L3 countdown (0x24DE cell, 0x24F2 pack;
    # zero below level 3) is running: then only mode_detect_chg_dch decides,
    # and its 30-tick debounce is what uv_mask reads. Ends with the displaced
    # LDAB. Y is free here (stacked by the interrupt); X is the register
    # pointer and is kept. Changes Y and CCR, which the handler sets next.
    a.label('uv_mode')
    a.emit('FD24DE')                # LDY 24DE: Z iff no cell L3 countdown
    a.branch('26', 'uv_mode_skip')
    a.emit('FD24F2')                # LDY 24F2: pack
    a.branch('26', 'uv_mode_skip')
    a.emit('7C2BE6')                # STD work mode, as stock
    a.label('uv_mode_skip')
    a.emit('E6E028')                # LDAB 0x28,IX: the displaced stock instruction
    a.emit('0A')                    # RTC
    code = a.finish(external)
    assert len(code) <= UV_END - UV
    return code.ljust(UV_END - UV, b'\xA7'), a.labels


def receiver(labels):
    a = Assembler(0x5581)
    # Stock's CAN4 0x999 case body. CAN4 no longer carries commands, so it is
    # inert: it falls straight into the receiver epilogue without pushing the
    # temporary the stock body's LEAS would have popped. Restoring the stock
    # copy instead is not an option - it writes 2250/2252/2254/240E, which the
    # CAN1 command path now owns, so an unvalidated CAN4 frame could arm a
    # heater lease.
    a.branch('20', 'epilogue')

    a.label('sub')              # entry: IX = the CAN1 register pointer
    # No IDE test. The only caller is ISR_CAN1_rx, which has already matched
    # the assembled ID against 0x18EF7000 under mask 1FFFFF00; its standard
    # frame path (7f4c92) yields at most 0x7FF, so a standard frame can never
    # reach here. RTR is not covered by that match - 7f4c7c clears the bit from
    # the stack copy before the ID is assembled - so it is still tested.
    a.emit('E6E023 C401')       # LDAB IDR3 / ANDB RTR
    a.branch('26', 'not_cmd')
    a.emit('E6E02C C40F C108')  # LDAB DLC / ANDB #15 / CMPB #8
    a.branch('26', 'not_cmd')
    # System-state gate, restoring the acceptance condition the CAN4 path got
    # for free: the stock dispatcher at 7f4e39 admits a command only in state 3
    # or 4. ISR_CAN1_rx has no equivalent, so without this a lease armed while
    # the BMS is still booting keeps counting and fires on entry to state 3.
    # SUBD then an unsigned compare covers both ends in one test: states 0-2
    # wrap above 1, states 5 and up exceed it.
    a.emit('FC2BEC 830003 8C0001')  # LDD g_sys_state / SUBD #3 / CPD #1
    a.branch('22', 'not_cmd')       # BHI
    a.emit('ECE024 8CAA55')     # LDD data[0:2], CPD magic
    a.branch('26', 'try_mask')
    a.emit(f'4A{labels["heater_publish"]:04X}FE')
    a.branch('20', 'handled')
    a.label('try_mask')
    a.emit('8C4D53')
    a.branch('26', 'not_cmd')
    a.emit(f'4A{labels["mask_toggle"]:04X}FE')
    a.label('handled')
    # Was LDAB #1 / RTS. The stub sets the status request, restores B and
    # returns, so this stays three bytes for three.
    a.emit(f'06{labels["ack_stub"]:04X}')
    a.label('not_cmd')
    a.emit('C7 3D')             # Z set: the caller decides what to do with it
    assert a.base + len(a.data) <= RECOVERY
    a.emit('A7' * (RECOVERY - a.base - len(a.data)))
    # Return signed comparison flags: (charge L1 * 10 + 50) versus min probe.
    # Preserve IY across EMUL. D is caller scratch (saved by the PIT wrapper).
    # Low 16-bit product is exact for every signed 8-bit setting, including <0.
    a.emit('35 F62652 B716 CC000A 13 C30032 BC233C 31 3D')
    result = a.finish(dict(labels, epilogue=0x55CE))
    assert len(result) <= 0x55CE - 0x5581
    return result.ljust(0x55CE - 0x5581, b'\xA7'), a.labels


def can1_helpers(diffmask=False):
    # Replace only the existing copy regions; stock CAN1 pacing, IDs, send,
    # retries and bookkeeping remain at their original addresses.
    # diffmask (--diffmask) also clears 1392 0020 (temperature-difference L1)
    # and 0008 (cell-difference L1) while the lease runs: the APS-500 faults on
    # them (fault 51, docs/aps500-ws500.md). One operand byte, 3E -> 16.
    a = Assembler(0xACD3)
    a.emit('C608')
    a.jsr('mask_copy')
    a.branch('20', 'full_join')
    a.label('mask_copy')
    a.emit('1982 FE26E2 1848 1AE223AE')  # Y=caller SP, X=&table[index]
    a.emit('B62252 81A5')                # sample once per outgoing frame
    a.branch('26', 'raw_copy')
    a.label('masked_copy')
    a.emit('A630 8E23C3')               # postincrement X: source was 23C2
    a.branch('26', 'mask_low')
    a.emit('84EB')
    a.branch('20', 'copy_store')
    a.label('mask_low')
    a.emit('8E23C4')
    a.branch('26', 'mask_next')
    a.emit('8416' if diffmask else '843E')
    a.branch('20', 'copy_store')
    a.label('mask_next')
    a.emit('8E23C5')
    a.branch('26', 'copy_store')
    a.emit('84FE')
    a.label('copy_store')
    a.emit('6A70 53')
    a.branch('26', 'masked_copy')
    a.emit('3D')
    a.label('raw_copy')
    a.emit('A630 6A70 53')
    a.branch('26', 'raw_copy')
    a.emit('3D')
    assert a.base + len(a.data) == MASK_TICK
    a.label('mask_tick')
    a.emit('1C232C04')                  # replay original 100 ms task flag exactly once
    a.emit('B62252 81A5')
    a.branch('26', 'mask_tick_return')
    a.emit('1873240E')                  # DECW lease; armed implies nonzero at entry
    a.branch('26', 'mask_tick_return')
    a.emit('792252')                    # expiry restores normal reporting
    a.label('mask_tick_return')
    a.emit('0A')                        # caller's PSHD preserves D; X/Y untouched
    full = a.finish({'full_join': 0xAD2B})
    assert len(full) <= 88

    b = Assembler(0xAD8D)
    b.emit('F626E5 58')
    b.jsr('mask_copy')
    b.emit('87')                       # CLRA: original partial path uses SEX A,Y
    b.branch('20', 'partial_join')
    b.label('mask_toggle')
    b.emit('ECE026')                   # reserved bytes 2..5 must all be zero
    b.branch('26', 'toggle_return')
    b.emit('ECE028')
    b.branch('26', 'toggle_return')
    b.emit('ECE02A 7C240E')             # publish 16-bit lease while ISR has I=1
    b.branch('27', 'mask_disable')
    b.emit('86A5')
    b.branch('20', 'mask_publish')
    b.label('mask_disable')
    b.emit('87')
    b.label('mask_publish')
    b.emit('7A2252')                    # enable only nonzero leases; zero disarms
    b.label('toggle_return')
    b.emit('0A')
    assert b.base + len(b.data) == BUSY_RETURN
    b.label('busy_return')
    b.emit('06AE16')                    # nonzero CAN1 remainder: stock return
    assert b.base + len(b.data) <= 0xADB7
    b.emit('A7' * (0xADB7 - b.base - len(b.data)))
    b.label('heater_publish')
    b.emit('7C2250 ECE02A 7C2254 4A9139FE FC2254')
    b.branch('26', 'heater_return')
    b.emit('18792250')
    b.label('heater_return')
    b.emit('0A')
    partial = b.finish(dict(a.labels, partial_join=0xADCE))
    assert len(partial) == 65
    return full.ljust(88, b'\xA7'), partial, dict(a.labels, **b.labels)


def parse(raw):
    records, mem = [], {}
    widths = {0: 2, 1: 2, 2: 3, 3: 4, 5: 2, 6: 3, 7: 4, 8: 3, 9: 2}
    for line in raw.decode('ascii').splitlines():
        kind = int(line[1])
        b = bytes.fromhex(line[2:])
        if b[0] + 1 != len(b) or sum(b) & 255 != 255:
            raise ValueError('Invalid record length/checksum')
        width = widths[kind]
        addr = int.from_bytes(b[1:width+1], 'big')
        data = b[width+1:-1]
        records.append((kind, addr, data, width))
        if kind in (1, 2):
            for i, v in enumerate(data):
                key = (kind, addr+i)
                if key in mem:
                    raise ValueError('Overlapping records')
                mem[key] = v
    return records, mem


def srec(kind, addr, data, width):
    b = bytes([len(data)+width+1]) + addr.to_bytes(width, 'big') + data
    return f'S{kind}' + b.hex().upper() + f'{(~sum(b)) & 255:02X}'


def build(src, dst, with_cvl=False, with_slew=False, with_uv=False, with_diffmask=False, cvl_max=CVL_MAX):
    raw = Path(src).read_bytes()
    if hashlib.sha256(raw).hexdigest() != STOCK_SHA256:
        raise ValueError('Refusing input other than the reviewed stock V137.4.5 image')
    records, mem = parse(raw)
    decode = decoder()
    code, labels = routines(DECODE_BASE + len(decode))
    full, partial, can_labels = can1_helpers(with_diffmask)
    labels.update(can_labels)
    assert len(decode) + len(code) <= DECODE_END - DECODE_BASE
    # New flash first: the dispatcher's JMP needs the ack stub's address.
    status, status_labels = status_tx()
    labels.update(status_labels)
    # Pad to a whole 8-byte program phrase so the loader is never asked to
    # program a partial phrase in the new sector.
    status = status.ljust(len(status) + (-len(status)) % 8, b'\xA7')
    assert STATUS + len(status) <= STATUS_END, 'status block overruns the Tier 1 window'
    dispatch, dispatch_labels = receiver(labels)
    labels.update(dispatch_labels)
    # ISR_CAN1_rx: CALL the hook, then the stock branch on its returned flags.
    splice = (bytes.fromhex(f'4A{CAN1_WRAPPER:04X}FE')
              + bytes([0x26, CAN1_EXIT - (CAN1_SPLICE + 6)]) + b'\xA7')
    assert len(splice) == len(CAN1_SPLICE_STOCK)
    if bytes(mem[1, CAN1_SPLICE+i] for i in range(len(CAN1_SPLICE_STOCK))) != CAN1_SPLICE_STOCK:
        raise ValueError('Unexpected stock CAN1 receiver busy-test bytes')
    edits = [(1, DECODE_BASE, (decode + code).ljust(DECODE_END-DECODE_BASE, b'\xA7')),
             (1, 0x5581, dispatch),
             (1, CAN1_SPLICE, splice),
             (1, 0x4AE7, bytes.fromhex('16') + labels['tick'].to_bytes(2, 'big') + b'\xA7'),
             (2, 0xFE9139, output(labels)),
             (2, 0xFE8D33, bytes.fromhex('16') + labels['write'].to_bytes(2, 'big') + b'\xA7'*10),
             (2, 0xFEACD3, full), (2, 0xFEAD8D, partial),
             (2, BUSY_TAIL, BUSY_FIXED),
             (2, FAST_SPLICE, bytes.fromhex('16') + labels['fast'].to_bytes(2, 'big') + b'\xA7'),
             (2, TICK_SPLICE, bytes.fromhex('16') + labels['periodic'].to_bytes(2, 'big') + b'\xA7')]
    assert len(BUSY_FIXED) == len(BUSY_STOCK) == 0xAD6D - (BUSY_TAIL & 0xFFFF)
    assert 0 < BUSY_RETURN - 0xAD6C < 128
    if bytes(mem[2, BUSY_TAIL+i] for i in range(len(BUSY_STOCK))) != BUSY_STOCK:
        raise ValueError('Unexpected stock CAN1 busy-release bytes')
    for where, stock_bytes, what in ((FAST_SPLICE, FAST_SPLICE_STOCK, 'CAN1 transmitter'),
                                     (TICK_SPLICE, TICK_SPLICE_STOCK, '100 ms task tail')):
        if bytes(mem[2, where+i] for i in range(len(stock_bytes))) != stock_bytes:
            raise ValueError(f'Unexpected stock bytes at the {what} splice')
    for kind, addr, data in edits:
        for i, v in enumerate(data):
            assert (kind, addr+i) in mem
            mem[kind, addr+i] = v
    # Added records: 32 data bytes each, matching stock, inserted in ascending
    # order directly after the last stock record below STATUS so the address
    # stream stays monotonic.
    added = [(1, STATUS + off, status[off:off+32], 2) for off in range(0, len(status), 32)]
    for kind, addr, data, _ in added:
        for i, v in enumerate(data):
            assert (kind, addr+i) not in mem, 'added record overlaps stock flash'
            mem[kind, addr+i] = v
    at = max(i for i, r in enumerate(records) if r[0] == 1 and r[1] < STATUS)
    assert records[at][1] + len(records[at][2]) <= STATUS, 'stock record runs into the window'
    records[at+1:at+1] = added
    if cvl_max != CVL_MAX and not with_cvl:
        raise ValueError('--cvl570 changes the CVL clamp; add --cvl')
    if with_cvl:
        cvl_code, cvl_labels = cvl(cvl_max)
        for where, stock_bytes, target in CVL_SPLICES:
            if bytes(mem[2, where+i] for i in range(len(stock_bytes))) != stock_bytes:
                raise ValueError(f'Unexpected stock bytes at the CVL splice {where:06X}')
            for i, v in enumerate(bytes.fromhex('16') + cvl_labels[target].to_bytes(2, 'big')):
                mem[2, where+i] = v
        # One 32-byte record, the same shape as stock's, directly after the
        # last stock page FE record (FE:BFC0-BFDF), inside the sector it opens.
        assert all((2, CVL+i) not in mem for i in range(len(cvl_code))), 'CVL overlaps stock flash'
        for i, v in enumerate(cvl_code):
            mem[2, CVL+i] = v
        at = max(i for i, r in enumerate(records) if r[0] == 2 and r[1] >> 16 == CVL >> 16)
        assert records[at][1] + len(records[at][2]) == CVL, 'page FE tail is not where expected'
        records[at+1:at+1] = [(2, CVL, cvl_code, 3)]
        labels.update(cvl_labels)
    if with_slew:
        slew_code, slew_labels = slew(with_slew)
        if bytes(mem[2, SLEW_SPLICE+i] for i in range(3)) != SLEW_SPLICE_STOCK:
            raise ValueError('Unexpected stock bytes at the slew splice')
        for i, v in enumerate(bytes.fromhex('16') + slew_labels['slew'].to_bytes(2, 'big')):
            mem[2, SLEW_SPLICE+i] = v
        # One 32-byte record directly after the status records, finishing the
        # Tier 1 window in the 1 KB sector they already occupy.
        assert STATUS + len(status) <= SLEW and SLEW + len(slew_code) == STATUS_END
        assert all((1, SLEW+i) not in mem for i in range(len(slew_code))), 'slew overlaps flash'
        for i, v in enumerate(slew_code):
            mem[1, SLEW+i] = v
        at = [r[:2] for r in records].index((1, added[-1][1]))
        records[at+1:at+1] = [(1, SLEW, slew_code, 2)]
        labels.update(slew_labels)
    if with_uv:
        if not with_slew:
            raise ValueError('--uvcharge publishes through the slew routine; add --slew or --slew256')
        uv_code, uv_labels = uvcharge(labels)
        cap_stock = bytes.fromhex('EC82 16') + labels['slew'].to_bytes(2, 'big')
        for kind, where, expect, target, tail in ((2, UV_CAP_SPLICE, cap_stock, 'uv_cap', b''),
                                                  (2, UV_COPY_SPLICE, UV_COPY_STOCK, 'uv_copy', UV_COPY_TAIL),
                                                  (1, UV_MODE_SPLICE, UV_MODE_STOCK, 'uv_mode', b'')):
            if bytes(mem[kind, where+i] for i in range(len(expect))) != expect:
                raise ValueError(f'Unexpected bytes at the undervoltage splice {where:06X}')
            new = (bytes.fromhex('4A') + uv_labels[target].to_bytes(2, 'big')
                   + bytes([UV >> 16]) + tail).ljust(len(expect), b'\xA7')
            assert len(new) == len(expect)
            for i, v in enumerate(new):
                mem[kind, where+i] = v
        # Four 32-byte records directly after the last stock page FC record
        # (FC:B760-B77C), inside the 1 KB sector FC:B400-B7FF it occupies.
        assert all((2, UV+i) not in mem for i in range(len(uv_code))), 'UV code overlaps flash'
        for i, v in enumerate(uv_code):
            mem[2, UV+i] = v
        at = max(i for i, r in enumerate(records) if r[0] == 2 and r[1] >> 16 == UV >> 16)
        assert records[at][1] + len(records[at][2]) <= UV, 'page FC tail is not where expected'
        assert (records[at][1] + len(records[at][2]) - 1) >> 10 == UV >> 10 == (UV_END - 1) >> 10
        records[at+1:at+1] = [(2, UV + off, uv_code[off:off+32], 3) for off in range(0, len(uv_code), 32)]
        labels.update(uv_labels)
    size = sum(len(r[2]) for r in records if r[0] in (1, 2))
    assert size <= MAX_IMAGE_BYTES, f'{size} bytes is larger than 137.4.1, the largest vendor image'
    out = []
    for kind, addr, data, width in records:
        if kind in (1, 2):
            data = bytes(mem[kind, addr+i] for i in range(len(data)))
        out.append(srec(kind, addr, data, width))
    Path(dst).write_bytes(('\r\n'.join(out)+'\r\n').encode('ascii'))
    print(f'Patched {dst}: {len(decode)} decoder + {len(code)} helper bytes '
          f'fit inside {DECODE_END-DECODE_BASE} existing bytes')
    print(f'Status broadcast: {len(status)} bytes at {STATUS:04X}-{STATUS+len(status)-1:04X} '
          f'in {len(added)} added records; {STATUS_END-STATUS-len(status)} bytes of the window spare')
    print('Routine addresses:', ', '.join(f'{k}={v:04X}' for k,v in labels.items()))
    print(f'Image: {size} programmed bytes ({MAX_IMAGE_BYTES - size} under 137.4.1, the largest vendor image)')


if __name__ == '__main__':
    import argparse
    p = argparse.ArgumentParser(allow_abbrev=False, description='Build a patched V137.4.5 image from the exact stock file. '
                                'The published combinations are in recipes/recipes.json (tools/build_recipe.py).')
    p.add_argument('stock', help="Renogy's stock V137.4.5[20240719a].s19")
    p.add_argument('out', help='the patched .s19 to write')
    p.add_argument('--cvl', action='store_true', help='charge voltage limit from IR3 (52.0-56.0 V)')
    p.add_argument('--cvl570', action='store_true', help='with --cvl: raise the ceiling to 57.0 V')
    p.add_argument('--slew', action='store_true', help='charge current slew, 1/128 per pass')
    p.add_argument('--slew256', action='store_true', help='charge current slew, 1/256 per pass')
    p.add_argument('--uvcharge', action='store_true', help='undervoltage charging (needs a slew flag)')
    p.add_argument('--diffmask', action='store_true', help='mask lease also hides the difference L1 alarms')
    a = p.parse_args()
    if Path(a.out).resolve() == Path(a.stock).resolve():
        p.error('the output would overwrite your stock file; choose another file name')
    try:
        build(a.stock, a.out, with_cvl=a.cvl, with_slew=256 if a.slew256 else 128 if a.slew else 0,
              with_uv=a.uvcharge, with_diffmask=a.diffmask, cvl_max=CVL_MAX_570 if a.cvl570 else CVL_MAX)
    except (ValueError, OSError) as e:
        sys.exit(f'error: {e}')
