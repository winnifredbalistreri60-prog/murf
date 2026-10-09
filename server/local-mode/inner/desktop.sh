#!/bin/bash
# Рабочий стол бота Hermes (Xvnc + XFCE) вне инструментов агента: desktop.sh start|stop|touch
PY=$(ls -d /root/.hermes/tools/python-*/bin/python3 2>/dev/null | sort -V | tail -1)
[ -x "$PY" ] || { echo "нет python Hermes"; exit 2; }
exec "$PY" -I -c "
import os, sys
sys.path.insert(0, '/root/.hermes/hermes-agent'); sys._hermes_pin_default_home = True
os.environ.setdefault('HERMES_HOME', '/root/.hermes')
import hermes_bootstrap
from tools.bot_desktop import runtime as r
a = sys.argv[1] if len(sys.argv) > 1 else 'start'
def rfb_ok():
    # отвечает ли VNC на сокете ИЗ ЭТОГО proot: в Termux-proot длинные пути сокетов у каждого proot-процесса свои,
    # и «живой» рабочий стол, запущенный другим proot (прошлая сессия Termux), отсюда недоступен
    import socket
    p = os.path.join(os.environ['HERMES_HOME'], 'bot-desktop', 'rfb.sock')
    s = socket.socket(socket.AF_UNIX); s.settimeout(3)
    try:
        s.connect(p); return s.recv(12).startswith(b'RFB ')
    except OSError:
        return False
    finally:
        s.close()
if a == 'start':
    if r.published_env().get('DISPLAY') and not rfb_ok():
        print('рабочий стол числится запущенным, но VNC не отвечает — перезапуск', flush=True)
        try: r.stop()
        except Exception as e: print('stop:', e, flush=True)
    if not r.published_env().get('DISPLAY'):
        # остатки после аварийного завершения (Android убил Termux): блокировки xauth и X-сервера
        import glob, subprocess
        bd = os.path.join(os.environ['HERMES_HOME'], 'bot-desktop')
        for f in glob.glob(os.path.join(bd, 'Xauthority-[cl]')):
            try: os.unlink(f)
            except OSError: pass
        if subprocess.run(['pgrep', '-x', 'Xvnc|Xtigervnc'], capture_output=True).returncode != 0:
            for f in glob.glob('/tmp/.X*-lock') + glob.glob('/tmp/.tX*-lock') + glob.glob('/tmp/.X11-unix/X*'):
                try: os.unlink(f)
                except OSError: pass
        r.start(wait_seconds=40)
    r.touch_activity()
elif a == 'stop':
    r.stop()
elif a == 'touch':
    r.touch_activity()
print('DISPLAY=' + str(r.published_env().get('DISPLAY')))
" "$@"
