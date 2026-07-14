import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { NgxPermissionsService, NgxRolesService } from 'ngx-permissions';
import { catchError, of } from 'rxjs';
import { Menu, MenuService } from './menu.service';

@Injectable({
  providedIn: 'root',
})
export class StartupService {
  private readonly http = inject(HttpClient);
  private readonly menuService = inject(MenuService);
  private readonly permissonsService = inject(NgxPermissionsService);
  private readonly rolesService = inject(NgxRolesService);

  /**
   * Behavio = tool lokal tanpa auth: menu dimuat langsung dari data/menu.json
   * (bukan lewat login/token).
   */
  load() {
    return new Promise<void>(resolve => {
      this.http
        .get<{ menu: Menu[] }>('data/menu.json')
        .pipe(catchError(() => of({ menu: [] as Menu[] })))
        .subscribe(res => {
          this.setPermissions();
          this.setMenu(res.menu ?? []);
          resolve();
        });
    });
  }

  private setMenu(menu: Menu[]) {
    this.menuService.addNamespace(menu, 'menu');
    this.menuService.set(menu);
  }

  private setPermissions() {
    const permissions = ['canAdd', 'canDelete', 'canEdit', 'canRead'];
    this.permissonsService.loadPermissions(permissions);
    this.rolesService.flushRoles();
    this.rolesService.addRoles({ ADMIN: permissions });
  }
}
