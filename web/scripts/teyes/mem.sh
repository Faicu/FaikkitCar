grep -E "MemTotal|MemFree|MemAvailable|SwapTotal|SwapFree" /proc/meminfo; cat /proc/loadavg; echo ---; ps -A -o RSS,%CPU,NAME -k -RSS | awk 'NR==1||$1>30000'
