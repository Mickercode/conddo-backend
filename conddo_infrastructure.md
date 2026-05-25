# CONDDO.IO — Backend Infrastructure Guide

> **Audience:** Engineering team and AI coding agents provisioning and maintaining the Conddo.io backend infrastructure.
> **Scope:** Everything from server provisioning to service configuration, deployment, monitoring, and the Conddo Studio internal tool backend.
> **Version:** 1.0 — 2026

---

## Table of Contents

1. [Infrastructure Overview](#1-infrastructure-overview)
2. [Server Provisioning](#2-server-provisioning)
3. [System Dependencies](#3-system-dependencies)
4. [Directory Structure](#4-directory-structure)
5. [Environment Configuration](#5-environment-configuration)
6. [PostgreSQL Setup](#6-postgresql-setup)
7. [Redis Setup](#7-redis-setup)
8. [MinIO Setup](#8-minio-setup)
9. [Nginx Configuration](#9-nginx-configuration)
10. [Docker and Docker Compose](#10-docker-and-docker-compose)
11. [Spring Boot Services](#11-spring-boot-services)
12. [Conddo Studio Backend](#12-conddo-studio-backend)
13. [Jobs Board Backend](#13-jobs-board-backend)
14. [Brevo Integration](#14-brevo-integration)
15. [Paystack Integration](#15-paystack-integration)
16. [SSL and Certificates](#16-ssl-and-certificates)
17. [CI/CD Pipeline](#17-cicd-pipeline)
18. [Monitoring and Logging](#18-monitoring-and-logging)
19. [Backup Strategy](#19-backup-strategy)
20. [Security Hardening](#20-security-hardening)
21. [Runbooks — Common Operations](#21-runbooks--common-operations)

---

## 1. Infrastructure Overview

### What Gets Deployed

Conddo.io runs as a collection of Docker containers orchestrated with Docker Compose on a self-hosted VPS. Every service is containerised. Nothing runs directly on the host OS except Docker, Nginx, and system utilities.

```
┌─────────────────────────────────────────────────────────┐
│                    VPS (Hetzner CPX41)                  │
│                    Ubuntu 24.04 LTS                     │
│                                                         │
│  ┌──────────┐  ┌──────────────────────────────────────┐ │
│  │  Nginx   │  │         Docker Network               │ │
│  │  (host)  │  │                                      │ │
│  │  :80     │  │  conddo-gateway    :8080             │ │
│  │  :443    │  │  conddo-auth       :8081             │ │
│  └────┬─────┘  │  conddo-registry   :8082             │ │
│       │        │  conddo-studio     :8083             │ │
│       │        │  conddo-jobs       :8084             │ │
│       │        │  conddo-modules    :8085             │ │
│       │        │  postgres          :5432             │ │
│       │        │  redis             :6379             │ │
│       │        │  minio             :9000 :9001       │ │
│       │        │  prometheus        :9090             │ │
│       │        │  grafana           :3000             │ │
│       └────────┤                                      │ │
│                └──────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

### Server Specifications

| Resource | Specification | Rationale |
|---|---|---|
| Provider | Hetzner Cloud | Best price/performance for Nigeria latency |
| Plan | CPX41 | 8 vCPU, 16GB RAM, 240GB SSD |
| OS | Ubuntu 24.04 LTS | LTS — 5 years security support |
| Location | Helsinki or Nuremberg | Closest to West Africa with low latency |
| Bandwidth | 20TB/month included | Sufficient for early scale |
| Backups | Enabled (daily snapshots) | Automatic Hetzner backups |

### Domain Architecture

```
conddo.io               → Landing page (Next.js on Vercel)
app.conddo.io           → Business owner dashboard (Next.js on Vercel)
api.conddo.io           → API Gateway (this VPS)
*.conddo.io             → Tenant subdomains → API Gateway → tenant routing
studio.conddo.io        → Conddo Studio internal tool (this VPS)
jobs.conddo.io          → Jobs Board internal tool (this VPS)
minio.conddo.io         → MinIO console (restricted access)
grafana.conddo.io       → Monitoring dashboard (restricted access)
```

---

## 2. Server Provisioning

### 2.1 Initial Server Setup

```bash
# On your local machine — create the server via Hetzner CLI
# Install: brew install hcloud

hcloud server create \
  --name conddo-prod \
  --type cpx41 \
  --image ubuntu-24.04 \
  --location nbg1 \
  --ssh-key your-ssh-key-name

# Get the server IP
hcloud server describe conddo-prod | grep "Public Net"
# Note: REPLACE <SERVER_IP> throughout this document with your actual IP
```

### 2.2 First Login and Base Configuration

```bash
# SSH into the server
ssh root@<SERVER_IP>

# Update everything
apt update && apt upgrade -y

# Set the hostname
hostnamectl set-hostname conddo-prod

# Set timezone to UTC
timedatectl set-timezone UTC

# Create a non-root user for deployments
useradd -m -s /bin/bash deploy
usermod -aG sudo deploy
usermod -aG docker deploy

# Copy SSH keys to deploy user
mkdir -p /home/deploy/.ssh
cp /root/.authorized_keys /home/deploy/.ssh/authorized_keys
chown -R deploy:deploy /home/deploy/.ssh
chmod 700 /home/deploy/.ssh
chmod 600 /home/deploy/.ssh/authorized_keys

# Disable root SSH login
sed -i 's/PermitRootLogin yes/PermitRootLogin no/' /etc/ssh/sshd_config
sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart sshd

# Install essential tools
apt install -y \
  curl wget git unzip \
  htop iotop nethogs \
  ufw fail2ban \
  jq tree \
  build-essential

echo "Base setup complete"
```

### 2.3 Firewall Setup

```bash
# Configure UFW
ufw default deny incoming
ufw default allow outgoing

# Allow SSH (non-standard port for security)
ufw allow 2222/tcp comment 'SSH'

# Allow HTTP and HTTPS
ufw allow 80/tcp comment 'HTTP'
ufw allow 443/tcp comment 'HTTPS'

# Enable firewall
ufw --force enable
ufw status verbose

# Change SSH port
sed -i 's/#Port 22/Port 2222/' /etc/ssh/sshd_config
systemctl restart sshd

# Now reconnect on port 2222
# ssh -p 2222 deploy@<SERVER_IP>
```

---

## 3. System Dependencies

### 3.1 Install Docker

```bash
# Add Docker's official GPG key
apt install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

# Add the repository
echo \
  "deb [arch=$(dpkg --print-architecture) \
  signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  tee /etc/apt/sources.list.d/docker.list > /dev/null

# Install Docker
apt update
apt install -y \
  docker-ce \
  docker-ce-cli \
  containerd.io \
  docker-buildx-plugin \
  docker-compose-plugin

# Verify
docker --version
docker compose version

# Enable Docker on boot
systemctl enable docker
systemctl start docker

# Allow deploy user to run Docker without sudo
usermod -aG docker deploy

echo "Docker installed"
```

### 3.2 Install Java 21

```bash
# Install Temurin JDK 21 (Eclipse Adoptium)
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | gpg --dearmor | tee /etc/apt/trusted.gpg.d/adoptium.gpg > /dev/null

echo "deb https://packages.adoptium.net/artifactory/deb \
  $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" \
  | tee /etc/apt/sources.list.d/adoptium.list

apt update
apt install -y temurin-21-jdk

# Verify
java --version
# openjdk 21.x.x ...

# Set JAVA_HOME
echo 'export JAVA_HOME=/usr/lib/jvm/temurin-21' >> /etc/environment
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/environment
source /etc/environment

echo "Java 21 installed"
```

### 3.3 Install Maven

```bash
# Download Maven 3.9.x
MAVEN_VERSION=3.9.6
wget https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz
tar -xzf apache-maven-${MAVEN_VERSION}-bin.tar.gz -C /opt/
ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven

# Set Maven in PATH
cat >> /etc/environment << 'EOF'
MAVEN_HOME=/opt/maven
PATH=/opt/maven/bin:$PATH
EOF
source /etc/environment

# Verify
mvn --version

echo "Maven installed"
```

### 3.4 Install Nginx

```bash
apt install -y nginx
systemctl enable nginx
systemctl start nginx

# Verify
nginx -v
```

### 3.5 Install Certbot

```bash
apt install -y snapd
snap install core
snap refresh core
snap install --classic certbot
ln -s /snap/bin/certbot /usr/bin/certbot

echo "Certbot installed"
```

---

## 4. Directory Structure

```bash
# Create the full directory structure
mkdir -p /opt/conddo/{
  services/{auth,gateway,registry,studio,jobs,modules},
  config/{nginx,postgres,redis,minio},
  data/{postgres,redis,minio},
  logs/{nginx,services},
  scripts,
  backups,
  ssl
}

# Set ownership
chown -R deploy:deploy /opt/conddo

# The full structure:
# /opt/conddo/
# ├── docker-compose.yml          ← Main compose file
# ├── docker-compose.prod.yml     ← Production overrides
# ├── .env                        ← Environment variables (never in git)
# ├── .env.example                ← Template (committed to git)
# │
# ├── services/
# │   ├── auth/                   ← Auth service JAR + Dockerfile
# │   ├── gateway/                ← API Gateway JAR + Dockerfile
# │   ├── registry/               ← Module Registry JAR + Dockerfile
# │   ├── studio/                 ← Conddo Studio backend JAR + Dockerfile
# │   ├── jobs/                   ← Jobs Board backend JAR + Dockerfile
# │   └── modules/                ← All module JARs + Dockerfile
# │
# ├── config/
# │   ├── nginx/
# │   │   ├── nginx.conf
# │   │   └── sites/
# │   │       ├── api.conddo.io.conf
# │   │       ├── studio.conddo.io.conf
# │   │       └── jobs.conddo.io.conf
# │   ├── postgres/
# │   │   └── postgresql.conf
# │   ├── redis/
# │   │   └── redis.conf
# │   └── minio/
# │       └── config.env
# │
# ├── data/
# │   ├── postgres/               ← PostgreSQL data directory
# │   ├── redis/                  ← Redis persistence
# │   └── minio/                  ← Object storage
# │
# ├── logs/
# │   ├── nginx/
# │   └── services/
# │
# ├── scripts/
# │   ├── deploy.sh
# │   ├── backup.sh
# │   ├── restore.sh
# │   └── healthcheck.sh
# │
# └── backups/                    ← Database and config backups

echo "Directory structure created"
```

---

## 5. Environment Configuration

### 5.1 Generate Secrets

```bash
# Run this ONCE to generate all secrets
# Save the output securely — you cannot recover these

# Generate JWT RSA key pair
mkdir -p /opt/conddo/ssl/jwt
cd /opt/conddo/ssl/jwt

openssl genrsa -out jwt_private.pem 4096
openssl rsa -in jwt_private.pem -pubout -out jwt_public.pem

# Base64 encode for environment variable
JWT_PRIVATE=$(cat jwt_private.pem | base64 -w 0)
JWT_PUBLIC=$(cat jwt_public.pem | base64 -w 0)
echo "JWT_PRIVATE_KEY=$JWT_PRIVATE"
echo "JWT_PUBLIC_KEY=$JWT_PUBLIC"

# Generate strong passwords
echo "DB_PASSWORD=$(openssl rand -base64 32)"
echo "REDIS_PASSWORD=$(openssl rand -base64 32)"
echo "MINIO_ROOT_PASSWORD=$(openssl rand -base64 32)"
echo "STUDIO_SECRET=$(openssl rand -base64 32)"
echo "JOBS_SECRET=$(openssl rand -base64 32)"
```

### 5.2 The .env File

```bash
# /opt/conddo/.env
# NEVER commit this file to git
# Restrict permissions: chmod 600 /opt/conddo/.env

cat > /opt/conddo/.env << 'ENVFILE'
# ── APPLICATION ─────────────────────────────────────────────
APP_ENV=production
BASE_DOMAIN=conddo.io
API_BASE_URL=https://api.conddo.io
STUDIO_BASE_URL=https://studio.conddo.io
JOBS_BASE_URL=https://jobs.conddo.io
CORS_ALLOWED_ORIGINS=https://app.conddo.io,https://*.conddo.io,https://studio.conddo.io,https://jobs.conddo.io

# ── DATABASE ─────────────────────────────────────────────────
DB_HOST=postgres
DB_PORT=5432
DB_NAME=conddo
DB_USER=conddo_app
DB_PASSWORD=REPLACE_WITH_GENERATED_PASSWORD
DATABASE_URL=jdbc:postgresql://postgres:5432/conddo

# Studio has its own schema in the same database
STUDIO_DB_SCHEMA=studio
JOBS_DB_SCHEMA=jobs

# ── REDIS ────────────────────────────────────────────────────
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=REPLACE_WITH_GENERATED_PASSWORD
REDIS_URL=redis://:${REDIS_PASSWORD}@redis:6379

# ── MINIO ────────────────────────────────────────────────────
MINIO_ROOT_USER=conddo-admin
MINIO_ROOT_PASSWORD=REPLACE_WITH_GENERATED_PASSWORD
MINIO_URL=http://minio:9000
MINIO_PUBLIC_URL=https://minio.conddo.io
MINIO_BUCKET_ASSETS=conddo-assets
MINIO_BUCKET_STUDIO=conddo-studio
MINIO_BUCKET_JOBS=conddo-jobs

# ── JWT ──────────────────────────────────────────────────────
JWT_PRIVATE_KEY=REPLACE_WITH_BASE64_PRIVATE_KEY
JWT_PUBLIC_KEY=REPLACE_WITH_BASE64_PUBLIC_KEY
JWT_ACCESS_TOKEN_EXPIRY=900000
JWT_REFRESH_TOKEN_EXPIRY=2592000000

# ── STUDIO (internal auth — separate from main JWT) ──────────
STUDIO_JWT_SECRET=REPLACE_WITH_GENERATED_SECRET
STUDIO_JWT_EXPIRY=28800000

# ── JOBS BOARD ───────────────────────────────────────────────
JOBS_JWT_SECRET=REPLACE_WITH_GENERATED_SECRET
JOBS_JWT_EXPIRY=28800000

# ── NOTIFICATIONS ────────────────────────────────────────────
BREVO_API_KEY=REPLACE_WITH_BREVO_API_KEY
BREVO_SENDER_EMAIL=hello@conddo.io
BREVO_SENDER_NAME=Conddo.io
BREVO_SMS_SENDER=CondoIO

# ── PAYMENTS ─────────────────────────────────────────────────
PAYSTACK_SECRET_KEY=REPLACE_WITH_PAYSTACK_SECRET
PAYSTACK_PUBLIC_KEY=REPLACE_WITH_PAYSTACK_PUBLIC
PAYSTACK_WEBHOOK_SECRET=REPLACE_WITH_WEBHOOK_SECRET

# ── META ADS ─────────────────────────────────────────────────
META_APP_ID=REPLACE_WITH_META_APP_ID
META_APP_SECRET=REPLACE_WITH_META_APP_SECRET
META_MASTER_AD_ACCOUNT_ID=act_REPLACE_WITH_ACCOUNT_ID
META_ACCESS_TOKEN=REPLACE_WITH_LONG_LIVED_TOKEN

# ── AI ───────────────────────────────────────────────────────
CLAUDE_API_KEY=REPLACE_WITH_ANTHROPIC_API_KEY
CLAUDE_MODEL=claude-sonnet-4-20250514

# ── MONITORING ───────────────────────────────────────────────
GRAFANA_ADMIN_PASSWORD=REPLACE_WITH_GRAFANA_PASSWORD
PROMETHEUS_RETENTION=30d

# ── EXCHANGE RATE ────────────────────────────────────────────
EXCHANGE_RATE_API_KEY=REPLACE_WITH_FX_API_KEY
FX_MARGIN_PERCENT=4.0
ENVFILE

# Restrict permissions
chmod 600 /opt/conddo/.env
chown deploy:deploy /opt/conddo/.env

echo ".env file created. Fill in all REPLACE_WITH_ values."
```

---

## 6. PostgreSQL Setup

### 6.1 PostgreSQL Configuration

```bash
cat > /opt/conddo/config/postgres/postgresql.conf << 'EOF'
# Memory — tuned for 16GB RAM server
shared_buffers = 4GB
effective_cache_size = 12GB
maintenance_work_mem = 1GB
work_mem = 64MB
wal_buffers = 64MB

# Connections
max_connections = 200

# Write performance
synchronous_commit = off
checkpoint_completion_target = 0.9
wal_compression = on

# Query planner
random_page_cost = 1.1
effective_io_concurrency = 200
default_statistics_target = 100

# Logging
log_min_duration_statement = 1000
log_checkpoints = on
log_connections = on
log_disconnections = on
log_lock_waits = on
log_temp_files = 0
log_autovacuum_min_duration = 0

# Timezone
timezone = 'UTC'
log_timezone = 'UTC'
EOF
```

### 6.2 Database Initialisation Script

```sql
-- /opt/conddo/config/postgres/init.sql
-- Runs automatically on first container start

-- Create application user (not superuser)
CREATE USER conddo_app WITH PASSWORD 'REPLACED_BY_ENV';
CREATE USER conddo_readonly WITH PASSWORD 'REPLACED_BY_ENV';

-- Create main database
CREATE DATABASE conddo
    WITH
    OWNER = conddo_app
    ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE = 'en_US.UTF-8'
    TEMPLATE = template0;

-- Connect to the database
\c conddo;

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";
CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- Create schemas
CREATE SCHEMA IF NOT EXISTS studio AUTHORIZATION conddo_app;
CREATE SCHEMA IF NOT EXISTS jobs AUTHORIZATION conddo_app;
CREATE SCHEMA IF NOT EXISTS public;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE conddo TO conddo_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO conddo_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA studio TO conddo_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA jobs TO conddo_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO conddo_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA studio TO conddo_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA jobs TO conddo_app;

-- Read-only user for monitoring and reporting
GRANT CONNECT ON DATABASE conddo TO conddo_readonly;
GRANT USAGE ON SCHEMA public, studio, jobs TO conddo_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO conddo_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA studio TO conddo_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA jobs TO conddo_readonly;

-- Core platform tables (run before any service starts)

-- TENANTS
CREATE TABLE tenants (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                     TEXT NOT NULL,
    slug                     TEXT UNIQUE NOT NULL,
    vertical                 TEXT NOT NULL,
    plan                     TEXT NOT NULL DEFAULT 'starter',
    status                   TEXT NOT NULL DEFAULT 'onboarding'
                               CHECK (status IN ('onboarding','active','suspended','cancelled')),
    custom_domain            TEXT,
    paystack_customer_id     TEXT,
    paystack_subscription_id TEXT,
    metadata                 JSONB NOT NULL DEFAULT '{}',
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_slug ON tenants (slug);
CREATE INDEX idx_tenants_status ON tenants (status);
CREATE INDEX idx_tenants_vertical ON tenants (vertical);

-- USERS
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID REFERENCES tenants(id) ON DELETE CASCADE,
    email           TEXT UNIQUE,
    phone           TEXT UNIQUE NOT NULL,
    password_hash   TEXT NOT NULL,
    full_name       TEXT NOT NULL,
    role            TEXT NOT NULL
                      CHECK (role IN ('SUPER_ADMIN','TENANT_ADMIN','STAFF','CUSTOMER','REP')),
    is_active       BOOLEAN NOT NULL DEFAULT true,
    is_verified     BOOLEAN NOT NULL DEFAULT false,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_tenant_id ON users (tenant_id);
CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_phone ON users (phone);
CREATE INDEX idx_users_role ON users (tenant_id, role);

-- REFRESH TOKENS
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT UNIQUE NOT NULL,
    family_id   UUID NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);

-- OTP CODES
CREATE TABLE otp_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone       TEXT NOT NULL,
    code_hash   TEXT NOT NULL,
    purpose     TEXT NOT NULL
                  CHECK (purpose IN ('SIGNUP','LOGIN','PASSWORD_RESET')),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    attempts    INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_otp_codes_phone ON otp_codes (phone, purpose);

-- MODULE DEFINITIONS
CREATE TABLE module_definitions (
    module_id    TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    vertical     TEXT,
    min_plan     TEXT NOT NULL,
    config_yaml  TEXT NOT NULL,
    version      TEXT NOT NULL DEFAULT '1.0',
    is_active    BOOLEAN NOT NULL DEFAULT true,
    loaded_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- TENANT MODULES
CREATE TABLE tenant_modules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    module_id       TEXT NOT NULL REFERENCES module_definitions(module_id),
    status          TEXT NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE','SUSPENDED','DEACTIVATED')),
    config_override JSONB NOT NULL DEFAULT '{}',
    activated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deactivated_at  TIMESTAMPTZ,
    UNIQUE (tenant_id, module_id)
);

CREATE INDEX idx_tenant_modules_tenant ON tenant_modules (tenant_id, status);

-- AUDIT LOG
CREATE TABLE audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID,
    user_id       UUID,
    module        TEXT NOT NULL,
    action        TEXT NOT NULL,
    resource_type TEXT,
    resource_id   TEXT,
    before_state  JSONB,
    after_state   JSONB,
    ip_address    INET,
    user_agent    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_tenant ON audit_log (tenant_id, created_at DESC);
CREATE INDEX idx_audit_log_action ON audit_log (tenant_id, action);

-- EVENT STORE
CREATE TABLE event_store (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID,
    event_type    TEXT NOT NULL,
    source_module TEXT NOT NULL,
    payload       JSONB NOT NULL,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_event_store_tenant ON event_store (tenant_id, event_type, published_at DESC);
CREATE INDEX idx_event_store_type ON event_store (event_type, published_at DESC);

-- Apply RLS to all tenant tables
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_modules ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON users
    USING (tenant_id = current_setting('app.tenant_id', true)::UUID);

CREATE POLICY tenant_isolation ON tenant_modules
    USING (tenant_id = current_setting('app.tenant_id', true)::UUID);

CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id = current_setting('app.tenant_id', true)::UUID
           OR current_setting('app.role', true) = 'SUPER_ADMIN');

SELECT 'Core platform schema created.' AS status;
```

### 6.3 Studio Schema

```sql
-- /opt/conddo/config/postgres/studio_schema.sql
-- Conddo Studio — internal website production tool

\c conddo;
SET search_path TO studio, public;

-- STAFF ACCOUNTS (Studio users — not tenant users)
CREATE TABLE studio.staff (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           TEXT UNIQUE NOT NULL,
    password_hash   TEXT NOT NULL,
    full_name       TEXT NOT NULL,
    role            TEXT NOT NULL
                      CHECK (role IN ('DEVELOPER','QA_REVIEWER','TEAM_LEAD','ADMIN')),
    skills          TEXT[] NOT NULL DEFAULT '{}',
    -- e.g. {WEBSITE_BUILD, GRAPHIC_DESIGN, CONTENT_WRITING}
    is_active       BOOLEAN NOT NULL DEFAULT true,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- JOB TYPES
CREATE TABLE studio.job_types (
    id                TEXT PRIMARY KEY,
    display_name      TEXT NOT NULL,
    description       TEXT,
    colour            TEXT NOT NULL DEFAULT '#7C5CBF',
    assigned_to_roles TEXT[] NOT NULL,
    sla_hours         INTEGER NOT NULL,
    qa_required       BOOLEAN NOT NULL DEFAULT true,
    auto_create       BOOLEAN NOT NULL DEFAULT false,
    qa_checklist      JSONB NOT NULL DEFAULT '[]',
    is_active         BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed default job types
INSERT INTO studio.job_types (id, display_name, colour, assigned_to_roles, sla_hours, qa_required, auto_create, qa_checklist) VALUES
('WEBSITE_BUILD',    'Website Build',     '#7C5CBF', '{DEVELOPER}',          48, true, true,
 '[{"id":"no_placeholder","label":"No placeholder text anywhere","required":true},
   {"id":"logo_placed","label":"Logo correctly placed and sized","required":true},
   {"id":"brand_colours","label":"Brand colours applied consistently","required":true},
   {"id":"mobile_check","label":"Mobile view correct at 375px","required":true},
   {"id":"contact_accurate","label":"Contact details accurate","required":true},
   {"id":"cta_present","label":"CTA buttons present and labelled","required":true},
   {"id":"design_standard","label":"Meets design standard library","required":true},
   {"id":"copy_natural","label":"Copy reads naturally","required":true}]'),
('WEBSITE_REVISION', 'Website Revision',  '#A07FD4', '{DEVELOPER}',          24, true, false, '[]'),
('GRAPHIC_DESIGN',   'Graphic Design',    '#F59E0B', '{DESIGNER}',           24, true, false, '[]'),
('AD_CREATIVE',      'Ad Creative',       '#EF4444', '{DESIGNER}',           12, true, false, '[]'),
('BRAND_KIT',        'Brand Kit',         '#22C55E', '{DESIGNER}',           72, true, false, '[]'),
('CONTENT_WRITING',  'Content Writing',   '#3B82F6', '{WRITER}',             24, true, false, '[]');

-- JOBS
CREATE TABLE studio.jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_number      TEXT UNIQUE NOT NULL,
    -- e.g. WB-2847 for website build #2847
    job_type_id     TEXT NOT NULL REFERENCES studio.job_types(id),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    -- linked business
    title           TEXT NOT NULL,
    brief           JSONB NOT NULL DEFAULT '{}',
    -- full business brief snapshot
    assets          JSONB NOT NULL DEFAULT '[]',
    -- uploaded file references
    status          TEXT NOT NULL DEFAULT 'QUEUED'
                      CHECK (status IN (
                        'QUEUED','AVAILABLE','ASSIGNED','IN_PROGRESS',
                        'SUBMITTED','IN_REVIEW','REVISION','APPROVED',
                        'DELIVERED','ESCALATED','CANCELLED'
                      )),
    assigned_to     UUID REFERENCES studio.staff(id),
    assigned_at     TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    submitted_at    TIMESTAMPTZ,
    approved_at     TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    sla_deadline    TIMESTAMPTZ NOT NULL,
    sla_extended_by INTEGER NOT NULL DEFAULT 0,
    -- hours
    revision_count  INTEGER NOT NULL DEFAULT 0,
    ai_suggestions  JSONB NOT NULL DEFAULT '{}',
    studio_url      TEXT,
    -- link to the built website in Studio
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_studio_jobs_status ON studio.jobs (status, sla_deadline);
CREATE INDEX idx_studio_jobs_assigned ON studio.jobs (assigned_to, status);
CREATE INDEX idx_studio_jobs_tenant ON studio.jobs (tenant_id);
CREATE INDEX idx_studio_jobs_type ON studio.jobs (job_type_id, status);

-- JOB ACTIVITY LOG
CREATE TABLE studio.job_activity (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id      UUID NOT NULL REFERENCES studio.jobs(id) ON DELETE CASCADE,
    staff_id    UUID REFERENCES studio.staff(id),
    action      TEXT NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_studio_job_activity ON studio.job_activity (job_id, created_at DESC);

-- QA REVIEWS
CREATE TABLE studio.qa_reviews (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES studio.jobs(id),
    reviewer_id     UUID NOT NULL REFERENCES studio.staff(id),
    outcome         TEXT NOT NULL CHECK (outcome IN ('APPROVED','REVISION')),
    checklist       JSONB NOT NULL DEFAULT '{}',
    -- {checklistItemId: {passed: bool, note: text}}
    reviewer_notes  TEXT,
    positive_notes  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_studio_qa_reviews ON studio.qa_reviews (job_id, created_at DESC);

-- DESIGN STANDARD LIBRARY
CREATE TABLE studio.design_standards (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type_id TEXT NOT NULL REFERENCES studio.job_types(id),
    vertical    TEXT,
    title       TEXT NOT NULL,
    description TEXT,
    asset_url   TEXT NOT NULL,
    thumbnail   TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT true,
    added_by    UUID REFERENCES studio.staff(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- JOB NUMBER SEQUENCE (per type)
CREATE SEQUENCE studio.website_build_seq START 1000;
CREATE SEQUENCE studio.graphic_design_seq START 1000;
CREATE SEQUENCE studio.content_seq START 1000;
CREATE SEQUENCE studio.revision_seq START 1000;
CREATE SEQUENCE studio.brand_kit_seq START 1000;
CREATE SEQUENCE studio.ad_creative_seq START 1000;

SELECT 'Studio schema created.' AS status;
```

### 6.4 Jobs Board Schema

```sql
-- /opt/conddo/config/postgres/jobs_schema.sql
-- Mirrors studio schema for the Jobs Board view
-- The Jobs Board reads from studio schema directly
-- Additional tables for the board UI layer

\c conddo;
SET search_path TO jobs, studio, public;

-- STAFF REFRESH TOKENS (for Jobs Board auth)
CREATE TABLE jobs.staff_refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id    UUID NOT NULL REFERENCES studio.staff(id) ON DELETE CASCADE,
    token_hash  TEXT UNIQUE NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- STAFF NOTIFICATIONS
CREATE TABLE jobs.notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id    UUID NOT NULL REFERENCES studio.staff(id),
    type        TEXT NOT NULL,
    title       TEXT NOT NULL,
    message     TEXT NOT NULL,
    job_id      UUID REFERENCES studio.jobs(id),
    is_read     BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_jobs_notifications ON jobs.notifications (staff_id, is_read, created_at DESC);

-- STAFF PERFORMANCE CACHE (updated daily)
CREATE TABLE jobs.staff_performance (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id            UUID NOT NULL REFERENCES studio.staff(id),
    period_month        DATE NOT NULL,
    jobs_completed      INTEGER NOT NULL DEFAULT 0,
    jobs_target         INTEGER NOT NULL DEFAULT 15,
    first_pass_qa_rate  NUMERIC(5,2) NOT NULL DEFAULT 0,
    avg_build_minutes   INTEGER NOT NULL DEFAULT 0,
    sla_compliance_rate NUMERIC(5,2) NOT NULL DEFAULT 100,
    revision_count      INTEGER NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (staff_id, period_month)
);

SELECT 'Jobs Board schema created.' AS status;
```

---

## 7. Redis Setup

```bash
cat > /opt/conddo/config/redis/redis.conf << 'EOF'
# Network
bind 0.0.0.0
port 6379
requirepass REPLACED_BY_ENV

# Memory
maxmemory 2gb
maxmemory-policy allkeys-lru

# Persistence
save 900 1
save 300 10
save 60 10000
appendonly yes
appendfsync everysec
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb

# Performance
lazyfree-lazy-eviction yes
lazyfree-lazy-expire yes
lazyfree-lazy-server-del yes

# Security
protected-mode yes
rename-command FLUSHDB ""
rename-command FLUSHALL ""
rename-command CONFIG ""
rename-command DEBUG ""

# Logging
loglevel notice
logfile ""

# Keyspace notifications (for job queue)
notify-keyspace-events "Ex"
EOF
```

---

## 8. MinIO Setup

```bash
cat > /opt/conddo/config/minio/config.env << 'EOF'
MINIO_ROOT_USER=conddo-admin
MINIO_ROOT_PASSWORD=REPLACED_BY_ENV
MINIO_VOLUMES=/data
MINIO_OPTS="--console-address :9001"

# Compression
MINIO_COMPRESSION_ENABLE=on
MINIO_COMPRESSION_EXTENSIONS=.txt,.log,.csv,.json,.xml

# Audit
MINIO_AUDIT_WEBHOOK_ENABLE=off
EOF

# Bucket creation script (run after MinIO starts)
cat > /opt/conddo/scripts/create_buckets.sh << 'BUCKETS'
#!/bin/bash
# Run once after MinIO container starts

# Wait for MinIO
sleep 5

# Configure mc alias
docker exec conddo-minio \
  mc alias set local http://localhost:9000 \
  conddo-admin "${MINIO_ROOT_PASSWORD}"

# Create buckets
for bucket in conddo-assets conddo-studio conddo-jobs conddo-backups; do
  docker exec conddo-minio mc mb local/${bucket} --ignore-existing
  echo "Created bucket: ${bucket}"
done

# Set lifecycle rules — delete temp files after 7 days
docker exec conddo-minio mc ilm add local/conddo-studio \
  --expiry-days 7 \
  --prefix "temp/"

echo "MinIO buckets configured"
BUCKETS

chmod +x /opt/conddo/scripts/create_buckets.sh
```

---

## 9. Nginx Configuration

### 9.1 Main Nginx Config

```nginx
# /opt/conddo/config/nginx/nginx.conf

user www-data;
worker_processes auto;
worker_rlimit_nofile 65535;
pid /run/nginx.pid;

events {
    worker_connections 4096;
    use epoll;
    multi_accept on;
}

http {
    # Basic settings
    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;
    types_hash_max_size 2048;
    server_tokens off;
    client_max_body_size 50M;

    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    # Logging
    log_format main '$remote_addr - $remote_user [$time_local] '
                    '"$request" $status $body_bytes_sent '
                    '"$http_referer" "$http_user_agent" '
                    'rt=$request_time ut=$upstream_response_time';

    access_log /var/log/nginx/access.log main;
    error_log /var/log/nginx/error.log warn;

    # Gzip
    gzip on;
    gzip_vary on;
    gzip_min_length 1024;
    gzip_proxied any;
    gzip_comp_level 6;
    gzip_types
        text/plain text/css text/xml
        application/json application/javascript
        application/rss+xml application/atom+xml
        image/svg+xml;

    # Rate limiting zones
    limit_req_zone $binary_remote_addr zone=api:10m rate=60r/m;
    limit_req_zone $binary_remote_addr zone=auth:10m rate=10r/m;
    limit_req_zone $binary_remote_addr zone=studio:10m rate=120r/m;

    # Security headers
    add_header X-Frame-Options DENY always;
    add_header X-Content-Type-Options nosniff always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Permissions-Policy "geolocation=(), microphone=(), camera=()" always;

    # Include site configs
    include /etc/nginx/sites/*.conf;
}
```

### 9.2 API Gateway Site

```nginx
# /opt/conddo/config/nginx/sites/api.conddo.io.conf

upstream api_gateway {
    server 127.0.0.1:8080;
    keepalive 32;
}

# HTTP redirect
server {
    listen 80;
    server_name api.conddo.io *.conddo.io;
    return 301 https://$server_name$request_uri;
}

# Tenant subdomains — route to API Gateway with tenant slug header
server {
    listen 443 ssl http2;
    server_name ~^(?<tenant_slug>.+)\.conddo\.io$;

    ssl_certificate /etc/letsencrypt/live/conddo.io/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/conddo.io/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # Exclude internal subdomains from tenant routing
    if ($tenant_slug ~* "^(api|studio|jobs|minio|grafana|www)$") {
        return 404;
    }

    location / {
        limit_req zone=api burst=20 nodelay;

        proxy_pass http://api_gateway;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Tenant-Slug $tenant_slug;

        proxy_connect_timeout 30s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }
}

# Main API domain
server {
    listen 443 ssl http2;
    server_name api.conddo.io;

    ssl_certificate /etc/letsencrypt/live/conddo.io/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/conddo.io/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # Auth endpoints — stricter rate limiting
    location /auth/ {
        limit_req zone=auth burst=5 nodelay;
        proxy_pass http://api_gateway;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # All other API endpoints
    location / {
        limit_req zone=api burst=30 nodelay;
        proxy_pass http://api_gateway;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Paystack webhook — no rate limiting, verify by signature
    location /api/v1/payments/webhook {
        limit_req off;
        proxy_pass http://api_gateway;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

### 9.3 Conddo Studio Site

```nginx
# /opt/conddo/config/nginx/sites/studio.conddo.io.conf

upstream conddo_studio {
    server 127.0.0.1:8083;
    keepalive 16;
}

server {
    listen 443 ssl http2;
    server_name studio.conddo.io;

    ssl_certificate /etc/letsencrypt/live/conddo.io/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/conddo.io/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;

    add_header Strict-Transport-Security "max-age=31536000" always;

    # Restrict to office/VPN IP (internal tool)
    # Uncomment and set your office IP for production:
    # allow <OFFICE_IP>;
    # deny all;

    location / {
        limit_req zone=studio burst=50 nodelay;

        proxy_pass http://conddo_studio;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket support (for live preview)
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }

    # File upload endpoint — larger body
    location /api/studio/assets/upload {
        client_max_body_size 100M;
        proxy_pass http://conddo_studio;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_read_timeout 300s;
    }
}
```

### 9.4 Jobs Board Site

```nginx
# /opt/conddo/config/nginx/sites/jobs.conddo.io.conf

upstream conddo_jobs {
    server 127.0.0.1:8084;
    keepalive 16;
}

server {
    listen 443 ssl http2;
    server_name jobs.conddo.io;

    ssl_certificate /etc/letsencrypt/live/conddo.io/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/conddo.io/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;

    add_header Strict-Transport-Security "max-age=31536000" always;

    location / {
        proxy_pass http://conddo_jobs;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;

        # SSE support (server-sent events for real-time notifications)
        proxy_set_header Cache-Control no-cache;
        proxy_buffering off;
        proxy_read_timeout 3600s;
    }
}
```

---

## 10. Docker and Docker Compose

### 10.1 Main Docker Compose

```yaml
# /opt/conddo/docker-compose.yml

version: '3.9'

networks:
  conddo-internal:
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.0.0/16

volumes:
  postgres_data:
    driver: local
    driver_opts:
      type: none
      device: /opt/conddo/data/postgres
      o: bind
  redis_data:
    driver: local
    driver_opts:
      type: none
      device: /opt/conddo/data/redis
      o: bind
  minio_data:
    driver: local
    driver_opts:
      type: none
      device: /opt/conddo/data/minio
      o: bind

services:

  # ── DATABASES ────────────────────────────────────────────

  postgres:
    image: postgres:16-alpine
    container_name: conddo-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: conddo
      POSTGRES_USER: conddo_app
      POSTGRES_PASSWORD: ${DB_PASSWORD}
      PGDATA: /var/lib/postgresql/data/pgdata
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./config/postgres/postgresql.conf:/etc/postgresql/postgresql.conf
      - ./config/postgres/init.sql:/docker-entrypoint-initdb.d/01_init.sql
      - ./config/postgres/studio_schema.sql:/docker-entrypoint-initdb.d/02_studio.sql
      - ./config/postgres/jobs_schema.sql:/docker-entrypoint-initdb.d/03_jobs.sql
    command: >
      postgres
        -c config_file=/etc/postgresql/postgresql.conf
        -c log_destination=stderr
    networks:
      - conddo-internal
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U conddo_app -d conddo"]
      interval: 10s
      timeout: 5s
      retries: 5
    deploy:
      resources:
        limits:
          memory: 6G

  redis:
    image: redis:7-alpine
    container_name: conddo-redis
    restart: unless-stopped
    command: >
      redis-server /etc/redis/redis.conf
      --requirepass ${REDIS_PASSWORD}
    volumes:
      - redis_data:/data
      - ./config/redis/redis.conf:/etc/redis/redis.conf
    networks:
      - conddo-internal
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD}", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    deploy:
      resources:
        limits:
          memory: 2G

  minio:
    image: minio/minio:latest
    container_name: conddo-minio
    restart: unless-stopped
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    volumes:
      - minio_data:/data
    command: server /data --console-address ":9001"
    networks:
      - conddo-internal
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 30s
      timeout: 10s
      retries: 3
    deploy:
      resources:
        limits:
          memory: 2G

  # ── APPLICATION SERVICES ─────────────────────────────────

  conddo-auth:
    build:
      context: ./services/auth
      dockerfile: Dockerfile
    container_name: conddo-auth
    restart: unless-stopped
    ports:
      - "127.0.0.1:8081:8081"
    environment:
      SPRING_PROFILES_ACTIVE: production
      SERVER_PORT: 8081
      DATABASE_URL: ${DATABASE_URL}
      REDIS_URL: ${REDIS_URL}
      JWT_PRIVATE_KEY: ${JWT_PRIVATE_KEY}
      JWT_PUBLIC_KEY: ${JWT_PUBLIC_KEY}
      JWT_ACCESS_TOKEN_EXPIRY: ${JWT_ACCESS_TOKEN_EXPIRY}
      JWT_REFRESH_TOKEN_EXPIRY: ${JWT_REFRESH_TOKEN_EXPIRY}
      BREVO_API_KEY: ${BREVO_API_KEY}
      BREVO_SENDER_EMAIL: ${BREVO_SENDER_EMAIL}
      BASE_DOMAIN: ${BASE_DOMAIN}
    networks:
      - conddo-internal
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
    deploy:
      resources:
        limits:
          memory: 1G

  conddo-gateway:
    build:
      context: ./services/gateway
      dockerfile: Dockerfile
    container_name: conddo-gateway
    restart: unless-stopped
    ports:
      - "127.0.0.1:8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: production
      SERVER_PORT: 8080
      REDIS_URL: ${REDIS_URL}
      JWT_PUBLIC_KEY: ${JWT_PUBLIC_KEY}
      AUTH_SERVICE_URL: http://conddo-auth:8081
      REGISTRY_SERVICE_URL: http://conddo-registry:8082
      MODULES_SERVICE_URL: http://conddo-modules:8085
      CORS_ALLOWED_ORIGINS: ${CORS_ALLOWED_ORIGINS}
    networks:
      - conddo-internal
    depends_on:
      - conddo-auth
      - conddo-registry
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3
    deploy:
      resources:
        limits:
          memory: 512M

  conddo-registry:
    build:
      context: ./services/registry
      dockerfile: Dockerfile
    container_name: conddo-registry
    restart: unless-stopped
    ports:
      - "127.0.0.1:8082:8082"
    environment:
      SPRING_PROFILES_ACTIVE: production
      SERVER_PORT: 8082
      DATABASE_URL: ${DATABASE_URL}
      REDIS_URL: ${REDIS_URL}
    volumes:
      - ./services/registry/config/modules:/app/modules:ro
      # Module YAML definitions — read only
    networks:
      - conddo-internal
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    deploy:
      resources:
        limits:
          memory: 512M

  conddo-modules:
    build:
      context: ./services/modules
      dockerfile: Dockerfile
    container_name: conddo-modules
    restart: unless-stopped
    ports:
      - "127.0.0.1:8085:8085"
    environment:
      SPRING_PROFILES_ACTIVE: production
      SERVER_PORT: 8085
      DATABASE_URL: ${DATABASE_URL}
      REDIS_URL: ${REDIS_URL}
      MINIO_URL: ${MINIO_URL}
      MINIO_ACCESS_KEY: ${MINIO_ROOT_USER}
      MINIO_SECRET_KEY: ${MINIO_ROOT_PASSWORD}
      BREVO_API_KEY: ${BREVO_API_KEY}
      PAYSTACK_SECRET_KEY: ${PAYSTACK_SECRET_KEY}
      CLAUDE_API_KEY: ${CLAUDE_API_KEY}
    networks:
      - conddo-internal
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      minio:
        condition: service_healthy
    deploy:
      resources:
        limits:
          memory: 2G

  conddo-studio:
    build:
      context: ./services/studio
      dockerfile: Dockerfile
    container_name: conddo-studio
    restart: unless-stopped
    ports:
      - "127.0.0.1:8083:8083"
    environment:
      SPRING_PROFILES_ACTIVE: production
      SERVER_PORT: 8083
      DATABASE_URL: ${DATABASE_URL}
      DATABASE_SCHEMA: studio
      REDIS_URL: ${REDIS_URL}
      MINIO_URL: ${MINIO_URL}
      MINIO_BUCKET: ${MINIO_BUCKET_STUDIO}
      STUDIO_JWT_SECRET: ${STUDIO_JWT_SECRET}
      STUDIO_JWT_EXPIRY: ${STUDIO_JWT_EXPIRY}
      CLAUDE_API_KEY: ${CLAUDE_API_KEY}
      CLAUDE_MODEL: ${CLAUDE_MODEL}
      MAIN_API_URL: http://conddo-gateway:8080
    networks:
      - conddo-internal
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      minio:
        condition: service_healthy
    deploy:
      resources:
        limits:
          memory: 1G

  conddo-jobs:
    build:
      context: ./services/jobs
      dockerfile: Dockerfile
    container_name: conddo-jobs
    restart: unless-stopped
    ports:
      - "127.0.0.1:8084:8084"
    environment:
      SPRING_PROFILES_ACTIVE: production
      SERVER_PORT: 8084
      DATABASE_URL: ${DATABASE_URL}
      DATABASE_SCHEMA: jobs
      REDIS_URL: ${REDIS_URL}
      MINIO_URL: ${MINIO_URL}
      MINIO_BUCKET: ${MINIO_BUCKET_JOBS}
      JOBS_JWT_SECRET: ${JOBS_JWT_SECRET}
      JOBS_JWT_EXPIRY: ${JOBS_JWT_EXPIRY}
      BREVO_API_KEY: ${BREVO_API_KEY}
      STUDIO_SERVICE_URL: http://conddo-studio:8083
      MAIN_API_URL: http://conddo-gateway:8080
    networks:
      - conddo-internal
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      conddo-studio:
        condition: service_healthy
    deploy:
      resources:
        limits:
          memory: 512M

  # ── MONITORING ───────────────────────────────────────────

  prometheus:
    image: prom/prometheus:latest
    container_name: conddo-prometheus
    restart: unless-stopped
    ports:
      - "127.0.0.1:9090:9090"
    volumes:
      - ./config/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.retention.time=${PROMETHEUS_RETENTION}'
      - '--web.enable-lifecycle'
    networks:
      - conddo-internal
    deploy:
      resources:
        limits:
          memory: 512M

  grafana:
    image: grafana/grafana:latest
    container_name: conddo-grafana
    restart: unless-stopped
    ports:
      - "127.0.0.1:3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_SERVER_DOMAIN: grafana.conddo.io
      GF_SERVER_ROOT_URL: https://grafana.conddo.io
    volumes:
      - ./data/grafana:/var/lib/grafana
    networks:
      - conddo-internal
    depends_on:
      - prometheus
    deploy:
      resources:
        limits:
          memory: 256M
```

### 10.2 Service Dockerfile Template

```dockerfile
# services/{service}/Dockerfile
# Same pattern for all Spring Boot services

FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root
RUN addgroup -g 1001 conddo && \
    adduser -u 1001 -G conddo -s /bin/sh -D conddo

WORKDIR /app

# Copy the built JAR
# Build first with: mvn clean package -DskipTests
COPY target/*.jar app.jar

# Change ownership
RUN chown conddo:conddo app.jar

USER conddo

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  -Dspring.backgroundpreinitializer.ignore=true"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

---

## 11. Spring Boot Services

### 11.1 application.yml Template

```yaml
# src/main/resources/application.yml
# Base configuration — overridden by application-production.yml

spring:
  application:
    name: conddo-{service-name}

  datasource:
    url: ${DATABASE_URL}
    username: conddo_app
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      connection-test-query: SELECT 1

  jpa:
    hibernate:
      ddl-auto: validate
      # NEVER use create or create-drop in production
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: false
        default_schema: public

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    validate-on-migrate: true

  data:
    redis:
      url: ${REDIS_URL}
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
  metrics:
    export:
      prometheus:
        enabled: true

logging:
  level:
    io.conddo: INFO
    org.springframework: WARN
    org.hibernate: WARN
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

### 11.2 Tenant Context Filter

```java
// src/main/java/io/conddo/core/tenant/TenantContextFilter.java
// Include in every service except Auth

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantSlugResolver slugResolver;
    private final EntityManager entityManager;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain)
            throws ServletException, IOException {

        try {
            // Get tenantId from JWT claims (set by JWT filter first)
            Authentication auth = SecurityContextHolder
                .getContext().getAuthentication();

            if (auth instanceof JwtAuthentication jwtAuth) {
                String tenantId = jwtAuth.getTenantId();

                // Set in ThreadLocal for application layer
                TenantContextHolder.setTenantId(tenantId);

                // Set PostgreSQL session variable for RLS
                entityManager.createNativeQuery(
                    "SET LOCAL app.tenant_id = '" + tenantId + "'"
                ).executeUpdate();

                // Set role for super admin bypass
                String role = jwtAuth.getRole();
                entityManager.createNativeQuery(
                    "SET LOCAL app.role = '" + role + "'"
                ).executeUpdate();
            }

            chain.doFilter(request, response);

        } finally {
            TenantContextHolder.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") ||
               path.startsWith("/health");
    }
}
```

---

## 12. Conddo Studio Backend

> **See also `SERVICE_TOPOLOGY.md`.** As built, §12 (Conddo Studio) and §13 (Jobs
> Board) are **one** service — `conddo-studio` (`studio` + `jobs` schemas, port 8083),
> already the unified jobs engine for website builds, ad creatives, graphic design,
> brand kits, and content writing. The website *runtime* (serving live sites) is a
> separate, not-yet-built service (`conddo-sites`); see the topology doc for the
> control/production/runtime split and the owner→job hand-off seam.

### 12.1 What the Studio Backend Does

The Studio backend is a standalone Spring Boot service running on port 8083. It serves the internal website builder used by the production team. It is completely separate from the main platform backend.

```
Responsibilities:
  - Staff authentication (separate JWT, not tenant JWT)
  - Job queue management (read/write to studio schema)
  - AI integration (Claude API for copy generation,
    image ranking, QA scanning)
  - Asset management (file uploads via MinIO)
  - Website preview generation
  - Real-time notifications via SSE
  - QA checklist management
  - Design standard library
```

### 12.2 Studio Service Structure

```
conddo-studio/
├── src/main/java/io/conddo/studio/
│   ├── StudioApplication.java
│   │
│   ├── auth/
│   │   ├── StudioAuthController.java
│   │   ├── StudioAuthService.java
│   │   └── StudioJwtService.java
│   │
│   ├── jobs/
│   │   ├── JobController.java
│   │   ├── JobService.java
│   │   ├── JobRepository.java
│   │   └── JobQueueService.java
│   │
│   ├── ai/
│   │   ├── AiAssistantService.java
│   │   ├── CopyGeneratorService.java
│   │   ├── ImageRankerService.java
│   │   ├── ColourPaletteService.java
│   │   └── QaScannerService.java
│   │
│   ├── assets/
│   │   ├── AssetController.java
│   │   └── AssetService.java
│   │
│   ├── notifications/
│   │   ├── NotificationController.java
│   │   ├── NotificationService.java
│   │   └── SseEmitterService.java
│   │
│   ├── qa/
│   │   ├── QaController.java
│   │   ├── QaService.java
│   │   └── DesignStandardService.java
│   │
│   └── staff/
│       ├── StaffController.java
│       └── StaffService.java
│
└── src/main/resources/
    ├── application.yml
    └── db/migration/
        └── (Flyway handles studio schema changes)
```

### 12.3 Studio application.yml

```yaml
# services/studio/src/main/resources/application.yml

spring:
  application:
    name: conddo-studio
  datasource:
    url: ${DATABASE_URL}
    hikari:
      maximum-pool-size: 10
  jpa:
    properties:
      hibernate:
        default_schema: studio
        # All JPA entities in studio schema

studio:
  jwt:
    secret: ${STUDIO_JWT_SECRET}
    expiry-ms: ${STUDIO_JWT_EXPIRY:28800000}
    # 8 hours — staff working day

  ai:
    claude:
      api-key: ${CLAUDE_API_KEY}
      model: ${CLAUDE_MODEL}
      max-tokens: 2000
      timeout-seconds: 30

  sla:
    # Alert thresholds (hours)
    amber-threshold: 24
    red-threshold: 6
    escalation-threshold: 2

  minio:
    url: ${MINIO_URL}
    bucket: ${MINIO_BUCKET:conddo-studio}
    presigned-url-expiry: 3600
    # 1 hour

  notifications:
    sse:
      timeout-seconds: 3600
      heartbeat-seconds: 30
```

### 12.4 AI Assistant Service

```java
// io/conddo/studio/ai/AiAssistantService.java

@Service
@Slf4j
public class AiAssistantService {

    private final ClaudeApiClient claudeClient;

    // System prompt layers — assembled at runtime
    private static final String LAYER_1_IDENTITY = """
        You are the AI assistant inside Conddo Studio,
        the internal website build tool for Conddo.io.
        Conddo.io builds professional websites for Nigerian SMEs.
        Every website must meet the Conddo.io design and copy standard.
        """;

    private static final String LAYER_2_COPY_RULES = """
        Write in plain, direct English.
        No AI slurps: never use 'seamless', 'robust', 'leverage',
        'scalable solutions', 'cutting-edge', 'empower', 'revolutionize'.
        Write like a confident person talking to a peer.
        Short sentences. Active voice. Real specifics.
        Avoid explaining what the business does — show it.
        """;

    public CopyGenerationResult generateSectionCopy(
            JobBrief brief,
            String sectionType,
            String verticalToneGuide) {

        String systemPrompt = buildSystemPrompt(verticalToneGuide);
        String userPrompt = buildUserPrompt(brief, sectionType);

        ClaudeResponse response = claudeClient.complete(
            ClaudeRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(1000)
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .build()
        );

        return parseCopyResponse(response.getContent());
    }

    public QaScanResult scanSubmission(
            Job job,
            String websiteContent) {

        String prompt = buildQaScanPrompt(job, websiteContent);

        ClaudeResponse response = claudeClient.complete(
            ClaudeRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(2000)
                .systemPrompt(buildQaSystemPrompt())
                .userPrompt(prompt)
                .build()
        );

        return parseQaScanResponse(response.getContent());
    }

    public List<ImageRanking> rankImages(
            List<String> imageUrls,
            String sectionType,
            String vertical) {

        // Use Claude vision to rank images
        List<ImageRanking> rankings = new ArrayList<>();

        for (String imageUrl : imageUrls) {
            ClaudeResponse response = claudeClient.completeWithImage(
                ClaudeRequest.builder()
                    .model("claude-sonnet-4-20250514")
                    .maxTokens(200)
                    .systemPrompt(buildImageRankingPrompt(
                        sectionType, vertical))
                    .imageUrl(imageUrl)
                    .userPrompt("Rate this image for website use. " +
                        "Return JSON: {score: 1-10, reason: string, " +
                        "recommendation: RECOMMENDED|ACCEPTABLE|NOT_RECOMMENDED}")
                    .build()
            );

            rankings.add(parseImageRanking(imageUrl, response));
        }

        rankings.sort(Comparator.comparing(ImageRanking::getScore).reversed());
        return rankings;
    }

    private String buildSystemPrompt(String verticalToneGuide) {
        return LAYER_1_IDENTITY + "\n" +
               LAYER_2_COPY_RULES + "\n" +
               verticalToneGuide;
    }
}
```

### 12.5 Job Queue Service

```java
// io/conddo/studio/jobs/JobQueueService.java

@Service
@Slf4j
public class JobQueueService {

    private final JobRepository jobRepo;
    private final StaffRepository staffRepo;
    private final NotificationService notifications;
    private final SseEmitterService sseService;

    // Called when a new business signs up
    // Triggered by TenantActivated event from main platform
    @Transactional
    public Job createWebsiteBuildJob(
            String tenantId,
            JobBrief brief) {

        String jobNumber = generateJobNumber("WB");
        LocalDateTime slaDeadline = LocalDateTime.now()
            .plusHours(48);

        Job job = Job.builder()
            .jobNumber(jobNumber)
            .jobTypeId("WEBSITE_BUILD")
            .tenantId(UUID.fromString(tenantId))
            .title("Website Build — " + brief.getBusinessName())
            .brief(brief.toJsonb())
            .status(JobStatus.AVAILABLE)
            .slaDeadline(slaDeadline)
            .build();

        job = jobRepo.save(job);

        // Log activity
        logActivity(job.getId(), null,
            "JOB_CREATED",
            "Job created from business signup");

        // Notify all available developers via SSE
        sseService.broadcastToRole(
            StaffRole.DEVELOPER,
            SseEvent.newJobAvailable(job)
        );

        log.info("Created job {} for tenant {}", jobNumber, tenantId);
        return job;
    }

    // Staff claims a job
    @Transactional
    public Job claimJob(UUID jobId, UUID staffId) {

        Job job = jobRepo.findById(jobId)
            .orElseThrow(() -> new JobNotFoundException(jobId));

        if (job.getStatus() != JobStatus.AVAILABLE) {
            throw new JobNotAvailableException(
                "Job " + job.getJobNumber() + " is not available");
        }

        job.setStatus(JobStatus.ASSIGNED);
        job.setAssignedTo(staffId);
        job.setAssignedAt(LocalDateTime.now());
        job = jobRepo.save(job);

        logActivity(jobId, staffId, "JOB_CLAIMED", null);

        return job;
    }

    // Scheduled SLA monitoring — runs every 15 minutes
    @Scheduled(cron = "0 */15 * * * *")
    public void monitorSlaDeadlines() {

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sixHoursFromNow = now.plusHours(6);
        LocalDateTime twoHoursFromNow = now.plusHours(2);

        // Jobs entering amber zone
        List<Job> amberJobs = jobRepo
            .findByStatusInAndSlaDeadlineBetween(
                List.of(JobStatus.ASSIGNED, JobStatus.IN_PROGRESS),
                sixHoursFromNow,
                now.plusHours(24)
            );

        // Jobs entering red zone
        List<Job> redJobs = jobRepo
            .findByStatusInAndSlaDeadlineBetween(
                List.of(JobStatus.ASSIGNED, JobStatus.IN_PROGRESS),
                twoHoursFromNow,
                sixHoursFromNow
            );

        // Jobs to auto-escalate
        List<Job> escalationJobs = jobRepo
            .findByStatusInAndSlaDeadlineBefore(
                List.of(JobStatus.ASSIGNED, JobStatus.IN_PROGRESS),
                twoHoursFromNow
            );

        redJobs.forEach(job -> {
            notifications.alertStaff(job.getAssignedTo(),
                "SLA ALERT: " + job.getJobNumber() +
                " is due in under 6 hours");
            sseService.broadcastToTeamLead(
                SseEvent.slaRisk(job, "RED"));
        });

        escalationJobs.forEach(job -> {
            escalateJob(job);
        });
    }

    @Transactional
    private void escalateJob(Job job) {
        job.setStatus(JobStatus.ESCALATED);
        jobRepo.save(job);

        logActivity(job.getId(), null, "JOB_ESCALATED",
            "Auto-escalated: SLA breach imminent");

        sseService.broadcastToTeamLead(
            SseEvent.escalation(job));

        notifications.alertTeamLead(
            "ESCALATION: " + job.getJobNumber() +
            " (" + job.getTitle() + ") — SLA at risk");
    }
}
```

---

## 13. Jobs Board Backend

### 13.1 What the Jobs Board Backend Does

The Jobs Board backend runs on port 8084. It reads from the studio schema for job data and provides the REST API that powers the Jobs Board React frontend.

```
Responsibilities:
  - Staff authentication (shares studio.staff table)
  - Job listing, filtering, and claiming
  - QA queue management
  - Staff performance calculation
  - Real-time updates via SSE
  - Admin operations (reassign, escalate, extend SLA)
  - Notifications (in-app + email via Brevo)
```

### 13.2 Jobs Board API Endpoints

```
Authentication
  POST   /api/jobs/auth/login
  POST   /api/jobs/auth/refresh
  POST   /api/jobs/auth/logout
  GET    /api/jobs/auth/me

Jobs — Staff
  GET    /api/jobs/my-jobs          My assigned/in-progress jobs
  GET    /api/jobs/available         Available jobs (filtered by my skills)
  GET    /api/jobs/{id}             Full job detail + brief
  POST   /api/jobs/{id}/claim       Claim an available job
  PATCH  /api/jobs/{id}/start       Mark as In Progress
  POST   /api/jobs/{id}/submit      Submit for QA (with notes)

QA
  GET    /api/jobs/qa/queue         All submitted jobs awaiting review
  POST   /api/jobs/qa/{id}/start    Start reviewing a job
  POST   /api/jobs/qa/{id}/approve  Approve submission
  POST   /api/jobs/qa/{id}/return   Return with revision feedback

Admin
  GET    /api/jobs/admin/dashboard  All jobs, all staff, SLA overview
  GET    /api/jobs/admin/staff      All staff with performance
  POST   /api/jobs/admin/staff      Add new staff member
  PATCH  /api/jobs/admin/{id}/reassign   Reassign job to different staff
  PATCH  /api/jobs/admin/{id}/extend-sla Extend SLA with reason
  PATCH  /api/jobs/admin/{id}/escalate   Manual escalation

Performance
  GET    /api/jobs/performance/me   My performance stats
  GET    /api/jobs/performance/{staffId}  Admin: any staff member

Notifications
  GET    /api/jobs/notifications    My unread notifications
  PATCH  /api/jobs/notifications/{id}/read

SSE (Server-Sent Events — real-time updates)
  GET    /api/jobs/events           SSE stream for current user
  Emits: job_available, job_updated, sla_alert, notification
```

---

## 14. Brevo Integration

### 14.1 Brevo Configuration

```java
// io/conddo/core/notification/BrevoConfig.java

@Configuration
public class BrevoConfig {

    @Value("${BREVO_API_KEY}")
    private String apiKey;

    @Bean
    public BrevoApiClient brevoApiClient() {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setApiKey(apiKey);
        return new BrevoApiClient(defaultClient);
    }
}
```

### 14.2 Notification Service

```java
// io/conddo/core/notification/NotificationService.java

@Service
@Slf4j
public class NotificationService {

    private final TransactionalEmailsApi emailApi;
    private final TransactionalSmsApi smsApi;

    // ── TRANSACTIONAL EMAIL ───────────────────────────────

    public void sendEmail(
            String toEmail,
            String toName,
            String subject,
            String htmlContent) {

        SendSmtpEmail email = new SendSmtpEmail();
        email.setSender(new SendSmtpEmailSender()
            .email("hello@conddo.io")
            .name("Conddo.io"));
        email.setTo(List.of(new SendSmtpEmailTo()
            .email(toEmail).name(toName)));
        email.setSubject(subject);
        email.setHtmlContent(htmlContent);

        try {
            emailApi.sendTransacEmail(email);
        } catch (ApiException e) {
            log.error("Failed to send email to {}: {}",
                toEmail, e.getMessage());
        }
    }

    // OTP email
    public void sendOtp(String phone, String otp) {
        SendTransacSms sms = new SendTransacSms();
        sms.setSender("CondoIO");
        sms.setRecipient(phone);
        sms.setContent(
            "Your Conddo.io verification code is: " +
            otp + "\nExpires in 10 minutes.");

        try {
            smsApi.sendTransacSms(sms);
        } catch (ApiException e) {
            log.error("Failed to send OTP SMS to {}: {}",
                phone, e.getMessage());
        }
    }

    // ── MARKETING CAMPAIGNS ───────────────────────────────

    public void addContactToList(
            String email,
            String firstName,
            String lastName,
            Long listId,
            Map<String, Object> attributes) {

        ContactsApi contactsApi = new ContactsApi();
        CreateContact contact = new CreateContact();
        contact.setEmail(email);
        contact.setListIds(List.of(listId));
        contact.setAttributes(attributes);

        try {
            contactsApi.createContact(contact);
        } catch (ApiException e) {
            log.warn("Contact may already exist: {}", e.getMessage());
            // Update instead
            updateContact(email, attributes, listId);
        }
    }
}
```

---

## 15. Paystack Integration

### 15.1 Webhook Verification

```java
// io/conddo/modules/payments/PaystackWebhookController.java

@RestController
@RequestMapping("/api/v1/payments")
public class PaystackWebhookController {

    private final PaystackWebhookService webhookService;

    @Value("${PAYSTACK_WEBHOOK_SECRET}")
    private String webhookSecret;

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Paystack-Signature") String signature) {

        // Verify signature first
        if (!verifySignature(payload, signature)) {
            log.warn("Invalid Paystack webhook signature");
            return ResponseEntity.status(401).build();
        }

        PaystackEvent event = parseEvent(payload);

        switch (event.getEvent()) {
            case "charge.success" ->
                webhookService.onPaymentSuccess(event);
            case "subscription.create" ->
                webhookService.onSubscriptionCreated(event);
            case "subscription.disable" ->
                webhookService.onSubscriptionCancelled(event);
            case "invoice.payment_failed" ->
                webhookService.onPaymentFailed(event);
        }

        return ResponseEntity.ok().build();
    }

    private boolean verifySignature(
            String payload, String signature) {

        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(
                webhookSecret.getBytes(), "HmacSHA512"));
            byte[] hash = mac.doFinal(payload.getBytes());
            String computed = Hex.encodeHexString(hash);
            return computed.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
```

---

## 16. SSL and Certificates

```bash
# Issue wildcard certificate for *.conddo.io
# This covers all tenant subdomains

certbot certonly \
  --dns-cloudflare \
  --dns-cloudflare-credentials /root/.cloudflare.ini \
  -d conddo.io \
  -d "*.conddo.io" \
  --preferred-challenges dns-01 \
  --agree-tos \
  --email hello@conddo.io

# Cloudflare credentials file
cat > /root/.cloudflare.ini << 'EOF'
dns_cloudflare_api_token = REPLACE_WITH_CLOUDFLARE_API_TOKEN
EOF
chmod 600 /root/.cloudflare.ini

# Auto-renewal cron
echo "0 3 * * * root certbot renew --quiet --post-hook 'nginx -s reload'" \
  >> /etc/cron.d/certbot

# Verify certificate
certbot certificates
openssl s_client -connect conddo.io:443 -servername conddo.io \
  | openssl x509 -noout -dates
```

---

## 17. CI/CD Pipeline

### 17.1 GitHub Actions — Build and Deploy

```yaml
# .github/workflows/deploy.yml

name: Build and Deploy

on:
  push:
    branches: [main]

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ${{ github.repository }}

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_DB: conddo_test
          POSTGRES_USER: conddo_app
          POSTGRES_PASSWORD: test_password
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
      redis:
        image: redis:7
        options: >-
          --health-cmd "redis-cli ping"
          --health-interval 10s

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Run tests
        run: mvn test -pl conddo-auth,conddo-gateway,conddo-registry
        env:
          DATABASE_URL: jdbc:postgresql://localhost:5432/conddo_test
          REDIS_URL: redis://localhost:6379

      - name: Security scan
        uses: anchore/scan-action@v3
        with:
          path: "."
          fail-build: true
          severity-cutoff: high

  build-and-push:
    needs: test
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    strategy:
      matrix:
        service: [auth, gateway, registry, studio, jobs, modules]

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Build JAR
        run: |
          cd services/${{ matrix.service }}
          mvn clean package -DskipTests

      - name: Log in to Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: services/${{ matrix.service }}
          push: true
          tags: |
            ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}-${{ matrix.service }}:latest
            ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}-${{ matrix.service }}:${{ github.sha }}

  deploy:
    needs: build-and-push
    runs-on: ubuntu-latest

    steps:
      - name: Deploy to production
        uses: appleboy/ssh-action@v1.0.0
        with:
          host: ${{ secrets.PROD_SERVER_IP }}
          username: deploy
          key: ${{ secrets.PROD_SSH_KEY }}
          port: 2222
          script: |
            cd /opt/conddo
            
            # Pull latest images
            docker compose pull
            
            # Rolling restart — zero downtime
            docker compose up -d \
              --remove-orphans \
              --no-deps \
              conddo-auth conddo-gateway conddo-registry \
              conddo-modules conddo-studio conddo-jobs
            
            # Health check
            sleep 30
            /opt/conddo/scripts/healthcheck.sh
            
            # Clean up old images
            docker image prune -f
            
            echo "Deployment complete: $(date)"
```

---

## 18. Monitoring and Logging

### 18.1 Prometheus Configuration

```yaml
# /opt/conddo/config/prometheus/prometheus.yml

global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'conddo-auth'
    static_configs:
      - targets: ['conddo-auth:8081']
    metrics_path: '/actuator/prometheus'

  - job_name: 'conddo-gateway'
    static_configs:
      - targets: ['conddo-gateway:8080']
    metrics_path: '/actuator/prometheus'

  - job_name: 'conddo-registry'
    static_configs:
      - targets: ['conddo-registry:8082']
    metrics_path: '/actuator/prometheus'

  - job_name: 'conddo-studio'
    static_configs:
      - targets: ['conddo-studio:8083']
    metrics_path: '/actuator/prometheus'

  - job_name: 'conddo-jobs'
    static_configs:
      - targets: ['conddo-jobs:8084']
    metrics_path: '/actuator/prometheus'

  - job_name: 'postgres'
    static_configs:
      - targets: ['postgres-exporter:9187']

  - job_name: 'redis'
    static_configs:
      - targets: ['redis-exporter:9121']

  - job_name: 'node'
    static_configs:
      - targets: ['node-exporter:9100']
```

### 18.2 Key Alerts

```yaml
# /opt/conddo/config/prometheus/alerts.yml

groups:
  - name: conddo-critical
    rules:
      - alert: ServiceDown
        expr: up == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Service {{ $labels.job }} is down"

      - alert: HighErrorRate
        expr: |
          rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.1
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "High error rate on {{ $labels.job }}"

      - alert: DatabaseConnectionPoolExhausted
        expr: |
          hikaricp_connections_active / hikaricp_connections_max > 0.9
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "DB connection pool near exhaustion"

      - alert: SLABreach
        expr: |
          conddo_studio_jobs_overdue_total > 0
        for: 0m
        labels:
          severity: critical
        annotations:
          summary: "{{ $value }} jobs have breached SLA"

      - alert: DiskSpaceWarning
        expr: |
          (node_filesystem_avail_bytes / node_filesystem_size_bytes) < 0.2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Disk space below 20%"
```

### 18.3 Health Check Script

```bash
#!/bin/bash
# /opt/conddo/scripts/healthcheck.sh

SERVICES=(
  "conddo-auth:8081"
  "conddo-gateway:8080"
  "conddo-registry:8082"
  "conddo-studio:8083"
  "conddo-jobs:8084"
  "conddo-modules:8085"
)

FAILED=0

for service in "${SERVICES[@]}"; do
  NAME="${service%%:*}"
  PORT="${service##*:}"

  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "http://localhost:${PORT}/actuator/health")

  if [ "$STATUS" = "200" ]; then
    echo "✓ ${NAME} — healthy"
  else
    echo "✗ ${NAME} — UNHEALTHY (HTTP ${STATUS})"
    FAILED=1
  fi
done

# Check databases
PG=$(docker exec conddo-postgres \
  pg_isready -U conddo_app -d conddo 2>&1)
if echo "$PG" | grep -q "accepting"; then
  echo "✓ postgres — healthy"
else
  echo "✗ postgres — UNHEALTHY"
  FAILED=1
fi

REDIS=$(docker exec conddo-redis \
  redis-cli -a "${REDIS_PASSWORD}" ping 2>&1)
if [ "$REDIS" = "PONG" ]; then
  echo "✓ redis — healthy"
else
  echo "✗ redis — UNHEALTHY"
  FAILED=1
fi

if [ $FAILED -eq 1 ]; then
  echo ""
  echo "HEALTH CHECK FAILED — Check logs immediately"
  exit 1
else
  echo ""
  echo "All services healthy"
  exit 0
fi
```

---

## 19. Backup Strategy

```bash
#!/bin/bash
# /opt/conddo/scripts/backup.sh
# Run via cron: 0 2 * * * /opt/conddo/scripts/backup.sh

set -e
source /opt/conddo/.env

BACKUP_DIR="/opt/conddo/backups"
DATE=$(date +%Y%m%d_%H%M%S)
RETENTION_DAYS=30

echo "Starting backup — ${DATE}"

# ── DATABASE BACKUP ──────────────────────────────────────────

DB_BACKUP="${BACKUP_DIR}/postgres_${DATE}.sql.gz"

docker exec conddo-postgres pg_dump \
  -U conddo_app conddo \
  | gzip > "${DB_BACKUP}"

echo "Database backup: ${DB_BACKUP} ($(du -sh ${DB_BACKUP} | cut -f1))"

# ── REDIS BACKUP ─────────────────────────────────────────────

REDIS_BACKUP="${BACKUP_DIR}/redis_${DATE}.rdb.gz"

docker exec conddo-redis \
  redis-cli -a "${REDIS_PASSWORD}" BGSAVE

sleep 5

docker cp conddo-redis:/data/dump.rdb - \
  | gzip > "${REDIS_BACKUP}"

echo "Redis backup: ${REDIS_BACKUP}"

# ── CONFIG BACKUP ────────────────────────────────────────────

CONFIG_BACKUP="${BACKUP_DIR}/config_${DATE}.tar.gz"

tar -czf "${CONFIG_BACKUP}" \
  --exclude /opt/conddo/data \
  --exclude /opt/conddo/backups \
  /opt/conddo/

echo "Config backup: ${CONFIG_BACKUP}"

# ── UPLOAD TO MINIO ──────────────────────────────────────────

for file in "${DB_BACKUP}" "${REDIS_BACKUP}" "${CONFIG_BACKUP}"; do
  docker exec conddo-minio mc cp \
    "/host${file}" \
    "local/conddo-backups/$(date +%Y/%m)/$(basename ${file})"
done

echo "Uploaded to MinIO"

# ── CLEAN OLD BACKUPS ─────────────────────────────────────────

find "${BACKUP_DIR}" -type f -mtime +${RETENTION_DAYS} -delete
echo "Deleted backups older than ${RETENTION_DAYS} days"

# ── VERIFY LATEST BACKUP ──────────────────────────────────────

if gunzip -t "${DB_BACKUP}" 2>/dev/null; then
  echo "✓ Database backup verified"
else
  echo "✗ Database backup CORRUPT — check immediately"
  exit 1
fi

echo "Backup complete — ${DATE}"
```

---

## 20. Security Hardening

```bash
#!/bin/bash
# /opt/conddo/scripts/security_hardening.sh
# Run once after initial server setup

# ── FAIL2BAN ─────────────────────────────────────────────────

cat > /etc/fail2ban/jail.local << 'EOF'
[DEFAULT]
bantime = 3600
findtime = 600
maxretry = 5
banaction = ufw

[sshd]
enabled = true
port = 2222
maxretry = 3
bantime = 86400

[nginx-limit-req]
enabled = true
filter = nginx-limit-req
port = http,https
logpath = /var/log/nginx/error.log
maxretry = 10
EOF

systemctl enable fail2ban
systemctl restart fail2ban

# ── KERNEL HARDENING ─────────────────────────────────────────

cat >> /etc/sysctl.conf << 'EOF'
# Network hardening
net.ipv4.ip_forward = 0
net.ipv4.conf.all.accept_redirects = 0
net.ipv4.conf.all.send_redirects = 0
net.ipv4.conf.all.accept_source_route = 0
net.ipv4.tcp_syncookies = 1
net.ipv4.conf.all.log_martians = 1

# Memory
vm.overcommit_memory = 1
vm.swappiness = 10
EOF

sysctl -p

# ── AUTOMATIC SECURITY UPDATES ───────────────────────────────

apt install -y unattended-upgrades
cat > /etc/apt/apt.conf.d/50unattended-upgrades << 'EOF'
Unattended-Upgrade::Allowed-Origins {
  "${distro_id}:${distro_codename}-security";
};
Unattended-Upgrade::AutoFixInterruptedDpkg "true";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
Unattended-Upgrade::Automatic-Reboot "false";
EOF

# ── DOCKER SECURITY ──────────────────────────────────────────

# Restrict Docker daemon
cat > /etc/docker/daemon.json << 'EOF'
{
  "live-restore": true,
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "100m",
    "max-file": "5"
  },
  "default-ulimits": {
    "nofile": {
      "Name": "nofile",
      "Hard": 65535,
      "Soft": 65535
    }
  },
  "userns-remap": "default",
  "no-new-privileges": true
}
EOF

systemctl restart docker

echo "Security hardening complete"
```

---

## 21. Runbooks — Common Operations

### Deploy a New Service Version

```bash
cd /opt/conddo

# Pull latest image for a specific service
docker compose pull conddo-studio

# Restart just that service
docker compose up -d --no-deps conddo-studio

# Watch logs during restart
docker compose logs -f conddo-studio

# Verify health
curl -s http://localhost:8083/actuator/health | jq .
```

### Add a New Module YAML Config

```bash
# 1. Create the YAML file
vim /opt/conddo/services/registry/config/modules/barbershop.yml

# 2. Reload the registry service (it watches the directory)
docker compose restart conddo-registry

# 3. Verify the module was loaded
curl -s http://localhost:8082/api/registry/modules \
  | jq '.[] | select(.moduleId == "barbershop")'
```

### Check SLA Status

```bash
# All jobs at risk right now
docker exec conddo-postgres psql -U conddo_app conddo << 'SQL'
SELECT
  job_number,
  title,
  status,
  assigned_to,
  sla_deadline,
  EXTRACT(EPOCH FROM (sla_deadline - NOW()))/3600 AS hours_remaining
FROM studio.jobs
WHERE status IN ('ASSIGNED', 'IN_PROGRESS')
  AND sla_deadline < NOW() + INTERVAL '6 hours'
ORDER BY sla_deadline ASC;
SQL
```

### Rotate JWT Keys

```bash
# Generate new RSA key pair
openssl genrsa -out /tmp/jwt_new_private.pem 4096
openssl rsa -in /tmp/jwt_new_private.pem \
  -pubout -out /tmp/jwt_new_public.pem

# Update .env
NEW_PRIVATE=$(cat /tmp/jwt_new_private.pem | base64 -w 0)
NEW_PUBLIC=$(cat /tmp/jwt_new_public.pem | base64 -w 0)

sed -i "s|JWT_PRIVATE_KEY=.*|JWT_PRIVATE_KEY=${NEW_PRIVATE}|" \
  /opt/conddo/.env
sed -i "s|JWT_PUBLIC_KEY=.*|JWT_PUBLIC_KEY=${NEW_PUBLIC}|" \
  /opt/conddo/.env

# Revoke all existing refresh tokens first
docker exec conddo-postgres psql -U conddo_app conddo \
  -c "UPDATE refresh_tokens SET revoked_at = NOW() WHERE revoked_at IS NULL;"

# Restart auth and gateway services
docker compose restart conddo-auth conddo-gateway

# All users will need to log in again
echo "JWT keys rotated. All sessions invalidated."
```

### Restore from Backup

```bash
# List available backups
ls -la /opt/conddo/backups/postgres_*.sql.gz | tail -10

# Restore a specific backup
BACKUP_FILE="/opt/conddo/backups/postgres_20260526_020000.sql.gz"

# Stop application services first
docker compose stop conddo-gateway conddo-auth \
  conddo-registry conddo-modules conddo-studio conddo-jobs

# Restore
gunzip -c "${BACKUP_FILE}" | docker exec -i conddo-postgres \
  psql -U conddo_app conddo

# Restart services
docker compose start conddo-gateway conddo-auth \
  conddo-registry conddo-modules conddo-studio conddo-jobs

# Verify
/opt/conddo/scripts/healthcheck.sh
```

### Scale a Service (Vertical)

```bash
# Increase memory limit for modules service under high load
# Edit docker-compose.yml:
# conddo-modules:
#   deploy:
#     resources:
#       limits:
#         memory: 4G  ← increase from 2G

docker compose up -d --no-deps conddo-modules
```

### View Real-Time Logs

```bash
# All services
docker compose logs -f --tail=100

# Specific service
docker compose logs -f conddo-studio

# Filter for errors only
docker compose logs -f | grep -E "ERROR|WARN|Exception"

# Studio job queue activity
docker compose logs -f conddo-studio \
  | grep -E "JOB_|SLA_|ESCALAT"
```

---

*Conddo.io Backend Infrastructure Guide · Handel Cores · Version 1.0 · 2026 · Confidential*
