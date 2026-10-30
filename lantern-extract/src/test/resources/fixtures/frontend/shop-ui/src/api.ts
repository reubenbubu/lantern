import axios from 'axios';

const BASE = import.meta.env.VITE_API;
const api = axios.create({ baseURL: import.meta.env.VITE_API });

export const products = () => api.get('/products');
export const product = (id: string) => api.get(`/products/${id}`);
export const addToCart = (item: unknown) =>
  fetch(`${BASE}/cart`, { method: 'POST', body: JSON.stringify(item) });
export const push = () => new WebSocket(import.meta.env.VITE_PUSH + '/live');
export const unknown = (url: string) => fetch(url);
