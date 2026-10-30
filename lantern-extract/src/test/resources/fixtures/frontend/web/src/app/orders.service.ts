import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Client } from '@stomp/stompjs';
import { environment } from '../environments/environment';

@Injectable({ providedIn: 'root' })
export class OrdersService {
  private readonly BASE_URI = '/orders';
  private http = inject(HttpClient);
  private cache = new Map<string, string>();

  list(page: number) {
    // A comment with 'quotes' and a call: this.http.get('/nowhere')
    return this.http.get<Order[]>(`${environment.apiUrl}${this.BASE_URI}?page=${page}`);
  }

  create(order: Order) {
    return this.http.post<Order>(
      environment.apiUrl + this.BASE_URI,
      order
    );
  }

  replace(id: string, order: Order) {
    return this.http.request('PUT', `${environment.apiUrl}${this.BASE_URI}/${id}`, { body: order });
  }

  cached(key: string) {
    return this.cache.get(key);
  }

  live() {
    return new Client({
      brokerURL: environment.socketUrl,
      reconnectDelay: 5000
    });
  }

  signIn() {
    // The browser leaves the app for the server's sign-in; a string's replace is no navigation.
    const next = this.address.location.replace(',', ' ');
    window.location.href = `${environment.apiUrl}/login?next=${next}`;
  }
}
