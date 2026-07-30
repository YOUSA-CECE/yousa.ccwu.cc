#!/data/data/com.termux/files/usr/bin/bash
TERMUX_HOME=/data/data/com.termux/files/home
export PATH=$TERMUX_HOME/usr/bin:$PATH
killall gunicorn python3 2>/dev/null
killall server-manager.sh 2>/dev/null
sleep 2
nohup $TERMUX_HOME/server-manager.sh > $TERMUX_HOME/server-mgr.log 2>&1 &
sleep 3
echo "DONE"
