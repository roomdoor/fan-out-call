import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 1,
  iterations: 1
};

export default function() {
  const response = http.get('http://localhost:8080/actuator/health');
  
  check(response, {
    'gateway health check passes': (r) => r.status === 200
  });
  
  console.log('k6 scaffold is working');
}
