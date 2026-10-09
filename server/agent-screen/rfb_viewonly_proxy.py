#!/usr/bin/env python3
"""RFB-прокси «только просмотр» для живого экрана агента (MURF).

websockify (путь /screen/websockify) -> этот прокси (unix-сокет) -> Xvnc rfb.sock.
Прокси разбирает поток клиент->сервер и ВЫБРАСЫВАЕТ все сообщения ввода:
KeyEvent(4), PointerEvent(5), ClientCutText(6), xvp(250), SetDesktopSize(251), QEMU(255).
Пропускаются только SetPixelFormat(0), SetEncodings(2), FramebufferUpdateRequest(3),
EnableContinuousUpdates(150), ClientFence(248). Неизвестное сообщение -> разрыв (fail-closed).
Поток сервер->клиент передаётся без изменений.
"""
import asyncio, os, struct, sys, logging

UPSTREAM = os.path.expanduser(os.environ.get("RFB_UPSTREAM", "~/.hermes/bot-desktop/rfb.sock"))
LISTEN = os.path.expanduser(os.environ.get("RFB_VIEW_LISTEN", "~/.cache/agent-screen/view.sock"))
log = logging.getLogger("rfb-view")


class Closed(Exception):
    pass


async def pump_server(reader, writer):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except Exception:
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def filter_client(creader, swriter, stats):
    async def rx(n):
        return await creader.readexactly(n)

    async def fwd(b):
        swriter.write(b)
        await swriter.drain()

    ver = await rx(12)
    if not ver.startswith(b"RFB 003."):
        raise Closed("bad version")
    await fwd(ver)
    minor = int(ver[8:11])
    if minor >= 7:
        sec = await rx(1)
        await fwd(sec)
        if sec[0] == 2:          # VNC auth: ответ 16 байт
            await fwd(await rx(16))
        elif sec[0] != 1:        # только None / VncAuth
            raise Closed("security type %d not supported" % sec[0])
    else:
        raise Closed("RFB 3.3 not supported")
    await fwd(await rx(1))       # ClientInit (shared flag)
    while True:
        t = (await rx(1))[0]
        if t == 0:
            await fwd(bytes([t]) + await rx(19))
        elif t == 2:
            h = await rx(3)
            n = struct.unpack(">H", h[1:3])[0]
            await fwd(bytes([t]) + h + await rx(4 * n))
        elif t == 3:
            await fwd(bytes([t]) + await rx(9))
        elif t == 150:
            await fwd(bytes([t]) + await rx(9))
        elif t == 248:
            h = await rx(8)
            await fwd(bytes([t]) + h + await rx(h[7]))
        elif t == 4:
            await rx(7); stats["drop"] += 1
        elif t == 5:
            await rx(5); stats["drop"] += 1
        elif t == 6:
            h = await rx(7)
            ln = struct.unpack(">i", h[3:7])[0]
            await rx(abs(ln)); stats["drop"] += 1
        elif t == 250:
            await rx(3); stats["drop"] += 1
        elif t == 251:
            h = await rx(7)
            await rx(16 * h[5]); stats["drop"] += 1
        elif t == 255:
            sub = (await rx(1))[0]
            if sub == 0:
                await rx(10)
            elif sub == 1:
                op = struct.unpack(">H", await rx(2))[0]
                if op == 2:
                    await rx(6)
            else:
                raise Closed("qemu sub %d" % sub)
            stats["drop"] += 1
        else:
            raise Closed("unknown client message %d" % t)


async def handle(creader, cwriter):
    stats = {"drop": 0}
    try:
        sreader, swriter = await asyncio.open_unix_connection(UPSTREAM)
    except Exception as e:
        log.warning("upstream unavailable: %s", e)
        cwriter.close()
        return
    down = asyncio.create_task(pump_server(sreader, cwriter))
    try:
        await filter_client(creader, swriter, stats)
    except (asyncio.IncompleteReadError, ConnectionError):
        pass
    except Closed as e:
        log.warning("closing: %s", e)
    finally:
        for w in (swriter, cwriter):
            try:
                w.close()
            except Exception:
                pass
        down.cancel()
        if stats["drop"]:
            log.info("view-only session closed, dropped %d input messages", stats["drop"])


async def main():
    os.makedirs(os.path.dirname(LISTEN), mode=0o700, exist_ok=True)
    try:
        os.unlink(LISTEN)
    except FileNotFoundError:
        pass
    old = os.umask(0o177)
    server = await asyncio.start_unix_server(handle, path=LISTEN)
    os.umask(old)
    os.chmod(LISTEN, 0o600)
    log.info("listening %s -> %s", LISTEN, UPSTREAM)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s", stream=sys.stdout)
    asyncio.run(main())
