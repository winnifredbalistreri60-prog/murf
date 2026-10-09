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
if a == 'start':
    if not r.published_env().get('DISPLAY'):
        r.start()
    r.touch_activity()
elif a == 'stop':
    r.stop()
elif a == 'touch':
    r.touch_activity()
print('DISPLAY=' + str(r.published_env().get('DISPLAY')))
" "$@"
