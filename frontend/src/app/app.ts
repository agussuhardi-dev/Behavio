import { Component, signal, inject, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';

interface Ping {
  service: string;
  status: string;
  time: string;
}

@Component({
  selector: 'app-root',
  imports: [],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements OnInit {
  private readonly http = inject(HttpClient);

  protected readonly backendStatus = signal<string>('menghubungkan…');
  protected readonly backendTime = signal<string>('');

  ngOnInit(): void {
    this.http.get<Ping>('/api/admin/v1/ping').subscribe({
      next: (r) => {
        this.backendStatus.set(r.status);
        this.backendTime.set(r.time);
      },
      error: () => this.backendStatus.set('OFFLINE')
    });
  }
}
