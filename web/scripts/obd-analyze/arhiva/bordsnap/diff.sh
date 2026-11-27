#!/bin/bash
# diff.sh <a> <b>
DIR="$(cd "$(dirname "$0")" && pwd)"
join -j1 $DIR/$1.txt $DIR/$2.txt | awk '$2!=$3{print $1, $2, "->", $3}'
