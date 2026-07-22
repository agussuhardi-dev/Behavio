import { Routes } from '@angular/router';
import { AdminLayout } from '@theme/admin-layout/admin-layout';
import { Error403 } from './routes/sessions/error-403';
import { Error404 } from './routes/sessions/error-404';
import { Error500 } from './routes/sessions/error-500';

export const routes: Routes = [
  {
    path: '',
    component: AdminLayout,
    children: [
      { path: '', redirectTo: 'simulators', pathMatch: 'full' },
      {
        path: 'simulators',
        loadChildren: () => import('./routes/simulators/simulators.routes').then(m => m.routes),
      },
      { path: 'qris', loadChildren: () => import('./routes/qris/qris.routes').then(m => m.routes) },
      { path: 'iso8583', loadChildren: () => import('./routes/iso/iso.routes').then(m => m.routes) },
      { path: '403', component: Error403 },
      { path: '404', component: Error404 },
      { path: '500', component: Error500 },
    ],
  },
  { path: '**', redirectTo: 'simulators' },
];
