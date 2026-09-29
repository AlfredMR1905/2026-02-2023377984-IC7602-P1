#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

for command in docker kubectl helm; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Falta instalar $command." >&2
        exit 1
    fi
done

node_name=$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}')
if [[ -z "$node_name" ]]; then
    echo 'No se encontró un nodo Kubernetes. Habilite Kubernetes en Docker Desktop.' >&2
    exit 1
fi

echo 'Configurando Supabase...'
kubectl create secret generic dns-supabase \
    --from-literal=url=https://wvopbidgfcairisohpdx.supabase.co \
    --from-literal=key=sb_publishable_ZGfvGbvJcS5b4PIsv1i3_A_Q4eSiIR3 \
    --dry-run=client -o yaml | kubectl apply -f -

for image in dns-api dns-interceptor dns-ui; do
    echo "Construyendo e importando $image..."
    docker build -t "$image:local" "./${image//-/_}"
    docker save "$image:local" | docker exec -i "$node_name" ctr -n k8s.io images import -
done

echo 'Instalando el chart de Helm...'
helm upgrade --install dns-platform ./helm/dns-platform
kubectl rollout restart deployment/dns-api deployment/dns-interceptor deployment/dns-ui
kubectl rollout status deployment/dns-api --timeout=180s
kubectl rollout status deployment/dns-interceptor --timeout=180s
kubectl rollout status deployment/dns-ui --timeout=180s

echo 'Probando DNS desde Kubernetes...'
kubectl run dns-demo --rm -i --restart=Never --image=busybox:1.36 -- nslookup -type=a ejemplo.test dns-interceptor

echo 'Probando DNS desde un cliente Linux fuera de Kubernetes...'
node_ip=$(kubectl get node "$node_name" -o jsonpath='{.status.addresses[?(@.type=="InternalIP")].address}')
node_port=$(kubectl get svc dns-interceptor -o jsonpath='{.spec.ports[0].nodePort}')
docker run --rm --network kind busybox:1.36 nslookup -type=a "-port=$node_port" ejemplo.test "$node_ip"

ui_port=8080
while (: > /dev/tcp/127.0.0.1/"$ui_port") 2>/dev/null; do
    ui_port=$((ui_port + 1))
done

echo "Proyecto listo. Abra http://localhost:$ui_port y mantenga esta terminal abierta."
kubectl port-forward svc/dns-ui "$ui_port:80"
