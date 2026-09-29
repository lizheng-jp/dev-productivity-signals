# Use Node.js 22 Alpine for a lightweight base image
FROM node:22-alpine AS base

# Install dependencies only when needed
FROM base AS builder

# Check https://github.com/nodejs/docker-node/tree/b4117f9333da4138b03a546ec926ef50a31506c3#nodealpine to understand why libc6-compat might be needed.
RUN apk add --no-cache libc6-compat

WORKDIR /app
ARG INTERNAL_API_BASE_URL
ARG BACKEND_URL
ARG NEXT_PUBLIC_AGENT_ENABLED=false
ENV INTERNAL_API_BASE_URL=${INTERNAL_API_BASE_URL}
ENV BACKEND_URL=${BACKEND_URL}
ENV NEXT_PUBLIC_AGENT_ENABLED=${NEXT_PUBLIC_AGENT_ENABLED}

# Copy pinned package metadata and install exact versions.
COPY package.json package-lock.json ./
RUN npm ci

# Rebuild the source code only when needed
# FROM base AS builder
# WORKDIR /app
# COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Build the Next.js application
RUN npm run build

# Production image, copy all the files and run next
FROM base AS runner
WORKDIR /app

ENV NODE_ENV=production

# Don't run as root
RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 nextjs

COPY --from=builder /app/public ./public

# Set the correct permission for prerender cache
RUN mkdir .next
RUN chown nextjs:nodejs .next

# Automatically leverage output traces to reduce image size
# https://nextjs.org/docs/advanced-features/output-file-tracing
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone/ ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nodejs /app/public ./public

USER nextjs

EXPOSE 3000

ENV PORT=3000
# set hostname to localhost
ENV HOSTNAME="0.0.0.0"

CMD ["node", "server.js"]
