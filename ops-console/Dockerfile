# No build step and no Node. The console is one HTML file, which is the right size
# for what it does - an operations tool nobody should have to compile.
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/index.html
EXPOSE 80
