#!/bin/bash
set -e

CLUSTER_NAME="iotdb-dev"

# 1. Delete existing cluster if it exists
if k3d cluster list | grep -q "$CLUSTER_NAME"; then
  echo "Deleting existing cluster $CLUSTER_NAME..."
  k3d cluster delete "$CLUSTER_NAME"
fi

# 2. Create k3d cluster with Traefik config mounted as HelmChartConfig
echo "Creating k3d cluster $CLUSTER_NAME with Traefik configuration..."
k3d cluster create "$CLUSTER_NAME" \
  --servers 1 \
  --port "8081:8081@loadbalancer" \
  --port "3306:3306@loadbalancer" \
  --port "6667:6667@loadbalancer" \
  --volume "$(pwd)/k8s/overlays/local/traefik-helmchartconfig.yaml:/var/lib/rancher/k3s/server/manifests/traefik-helmchartconfig.yaml@server:*"

# 3. Create namespace
echo "Ensuring namespace iotdb-dev exists..."
kubectl create namespace iotdb-dev --dry-run=client -o yaml | kubectl apply -f -

# 4. Create ConfigMaps for MySQL init (using main config.yaml)
echo "Creating ConfigMaps for MySQL init..."
kubectl create configmap mysql-init-config --from-file=config.yaml=config/config.yaml -n iotdb-dev -o yaml --dry-run=client | kubectl apply -f -
kubectl create configmap mysql-init-scripts --from-file=generate_dummy_data.py=scripts/generate_dummy_data.py -n iotdb-dev -o yaml --dry-run=client | kubectl apply -f -

# 5. Wait for Helm install job to complete (installs Traefik CRDs)
echo "Waiting for Traefik CRDs installation job to complete..."
kubectl wait --for condition=complete job/helm-install-traefik-crd -n kube-system --timeout=120s

# 6. Apply Kustomize manifests (IoTDB, Flink, MySQL, IngressRouteTCP)
echo "Applying Kustomize manifests for local overlay..."
kubectl apply -k k8s/overlays/local

echo "Waiting for MySQL to be ready..."
kubectl rollout status deployment/mysql -n iotdb-dev
echo "Waiting for IoTDB StatefulSet..."
kubectl rollout status statefulset/iotdb -n iotdb-dev

# 7. Run dummy data generation job
echo "Generating dummy data..."
kubectl apply -f k8s/overlays/local/dummy-data-job.yaml
kubectl wait --for=condition=complete job/generate-dummy-data -n iotdb-dev --timeout=120s
kubectl delete job/generate-dummy-data -n iotdb-dev

echo ""
echo "=============================================="
echo "Cluster is ready!"
echo ""
echo "Access from host machine:"
echo "  MySQL:   mysql -h 127.0.0.1 -P 3306 -u root -prootpassword testdb"
echo "  IoTDB:   localhost:6667"
echo "  Flink:   http://localhost:8081"
echo ""
echo "=============================================="
kubectl get pods -n iotdb-dev
